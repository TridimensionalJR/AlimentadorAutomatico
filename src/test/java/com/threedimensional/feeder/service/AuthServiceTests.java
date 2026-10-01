package com.threedimensional.feeder.service;

import com.threedimensional.feeder.config.JwtConfig;
import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The sign-in exchange end to end, short of Google itself: account creation and lookup run against
 * the real repository and the token is decoded by the real decoder, so what is asserted is what a
 * client would receive. Only {@link GoogleTokenVerifier} is replaced, since it is the one piece
 * that would otherwise need Google's keys.
 * <p>
 * A {@code @DataJpaTest} slice does not scan {@code @Service} classes, hence the explicit
 * {@code @Import}. {@link JwtConfig} supplies the encoder, the decoder and the clock.
 */
@DataJpaTest
@Import({AuthService.class, UserService.class, JwtService.class, JwtConfig.class})
class AuthServiceTests {

	private static final String ID_TOKEN = "google-id-token";
	private static final String GOOGLE_ID = "google-abc";
	private static final String EMAIL = "tiago@example.com";

	@Autowired
	private AuthService authService;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	@Qualifier("appJwtDecoder")
	private JwtDecoder appJwtDecoder;

	@MockitoBean
	private GoogleTokenVerifier googleTokenVerifier;

	// --- Sign-in ------------------------------------------------------------------------------

	/** The token's subject is the id of the account that was just created. */
	@Test
	void loginWithGoogle_shouldCreateAccountAndIssueToken_whenFirstLogin() {
		when(googleTokenVerifier.verify(ID_TOKEN)).thenReturn(new GoogleIdentity(GOOGLE_ID, EMAIL, "Tiago"));

		AuthService.LoginResult result = authService.loginWithGoogle(ID_TOKEN);

		assertThat(result.user().getId()).isNotNull();
		assertThat(result.user().getGoogleId()).isEqualTo(GOOGLE_ID);
		assertThat(userRepository.count()).isEqualTo(1);
		assertThat(appJwtDecoder.decode(result.accessToken().value()).getSubject())
				.isEqualTo(result.user().getId().toString());
	}

	/**
	 * A returning user gets a token for the account that already exists: same id, no second row.
	 * Nothing in the result tells the two cases apart.
	 */
	@Test
	void loginWithGoogle_shouldReuseTheAccount_whenAlreadyRegistered() {
		when(googleTokenVerifier.verify(ID_TOKEN)).thenReturn(new GoogleIdentity(GOOGLE_ID, EMAIL, "Tiago"));

		AuthService.LoginResult first = authService.loginWithGoogle(ID_TOKEN);
		AuthService.LoginResult again = authService.loginWithGoogle(ID_TOKEN);

		assertThat(again.user().getId()).isEqualTo(first.user().getId());
		assertThat(userRepository.count()).isEqualTo(1);
		assertThat(appJwtDecoder.decode(again.accessToken().value()).getSubject())
				.isEqualTo(first.user().getId().toString());
	}

	/** An untrusted credential must not leave a trace: no account is created for it. */
	@Test
	void loginWithGoogle_shouldNotCreateAnAccount_whenTokenIsInvalid() {
		when(googleTokenVerifier.verify(ID_TOKEN)).thenThrow(new InvalidGoogleTokenException("forged"));

		assertThatThrownBy(() -> authService.loginWithGoogle(ID_TOKEN))
				.isInstanceOf(InvalidGoogleTokenException.class);

		assertThat(userRepository.count()).isZero();
	}

	// --- Concurrent first login ---------------------------------------------------------------

	/**
	 * Two requests with the same credential both find no account, and the loser's insert violates
	 * the UNIQUE constraint at commit. The user service is mocked to stage that outcome exactly,
	 * which a single-threaded test could not otherwise produce: the first call fails the way the
	 * loser does, and the retry finds the row the winner committed.
	 */
	@Test
	void loginWithGoogle_shouldRetryOnce_whenAConcurrentFirstLoginWonTheRace() {
		when(googleTokenVerifier.verify(ID_TOKEN)).thenReturn(new GoogleIdentity(GOOGLE_ID, EMAIL, "Tiago"));
		User winner = userRepository.saveAndFlush(User.create(GOOGLE_ID, EMAIL, "Tiago"));
		UserService racingUserService = mock(UserService.class);
		when(racingUserService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago"))
				.thenThrow(new DataIntegrityViolationException("uk_users_google_id"))
				.thenReturn(winner);
		AuthService service = new AuthService(googleTokenVerifier, racingUserService, jwtService);

		AuthService.LoginResult result = service.loginWithGoogle(ID_TOKEN);

		assertThat(result.user().getId()).isEqualTo(winner.getId());
		verify(racingUserService, times(2)).getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");
	}

	/**
	 * A second failure is no longer the race, for instance an email that already belongs to another
	 * account, so it propagates instead of looping, and no token is issued for it.
	 */
	@Test
	void loginWithGoogle_shouldGiveUp_whenTheRetryFailsToo() {
		when(googleTokenVerifier.verify(ID_TOKEN)).thenReturn(new GoogleIdentity(GOOGLE_ID, EMAIL, "Tiago"));
		UserService brokenUserService = mock(UserService.class);
		when(brokenUserService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago"))
				.thenThrow(new DataIntegrityViolationException("uk_users_email"));
		JwtService neverCalled = mock(JwtService.class);
		AuthService service = new AuthService(googleTokenVerifier, brokenUserService, neverCalled);

		assertThatThrownBy(() -> service.loginWithGoogle(ID_TOKEN))
				.isInstanceOf(DataIntegrityViolationException.class);

		verify(brokenUserService, times(2)).getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");
		verify(neverCalled, never()).issue(any());
	}
}
