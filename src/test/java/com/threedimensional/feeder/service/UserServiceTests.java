package com.threedimensional.feeder.service;

import com.threedimensional.feeder.exception.UserHasFeedersException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.repository.FeederRepository;
import com.threedimensional.feeder.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The account lifecycle: a verified Google {@code sub} claim is the only way an account comes
 * into existence, and every later login re-syncs the profile the token holds. All service methods
 * end in a database write, so the repository layer is part of what is under test.
 * <p>
 * A {@code @DataJpaTest} slice does not scan {@code @Service} classes, hence the
 * {@code @Import(UserService.class)}.
 */
@DataJpaTest
@Import(UserService.class)
class UserServiceTests {

	private static final String GOOGLE_ID = "google-abc";
	private static final String EMAIL = "tiago@example.com";

	@Autowired
	private UserService userService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private FeederRepository feederRepository;

	@Autowired
	private EntityManager entityManager;

	// --- Sign-in upsert -----------------------------------------------------------------------

	/**
	 * The account is keyed by the token subject claim, never by the email: email is just profile
	 * data Google may change, google_id is the identity.
	 */
	@Test
	void getOrCreateByGoogle_shouldCreateAccount_whenFirstLogin() {
		User user = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");

		assertThat(user.getId()).isNotNull();
		assertThat(user.getGoogleId()).isEqualTo(GOOGLE_ID);
		assertThat(user.getEmail()).isEqualTo(EMAIL);
		assertThat(user.getName()).isEqualTo("Tiago");
	}

	/**
	 * Re-running the same login must neither create a second row nor touch the row already there.
	 * updated_at is the exact proof: it is set on creation, so what matters is that the second
	 * login leaves it exactly as it was - a rewrite would push it forward.
	 */
	@Test
	void getOrCreateByGoogle_shouldNotRewriteAnUnchangedAccount() {
		User first = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");
		Instant untouched = first.getUpdatedAt();

		User again = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");
		userRepository.flush();

		assertThat(again.getId()).isEqualTo(first.getId());
		assertThat(again.getUpdatedAt()).isEqualTo(untouched);
		assertThat(userRepository.count()).isEqualTo(1);
	}

	/**
	 * Google owns the profile, so a login that comes back with a new name must change the account
	 * in place - not create a second one - and now a profile change really happened, so updated_at
	 * moves forward past the value creation left there.
	 */
	@Test
	void getOrCreateByGoogle_shouldSyncProfile_whenNameChangedAtTheIdP() {
		User first = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");
		Instant atCreation = first.getUpdatedAt();

		User synced = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago Santana");
		userRepository.flush();

		assertThat(synced.getId()).isEqualTo(first.getId());
		assertThat(synced.getName()).isEqualTo("Tiago Santana");
		assertThat(synced.getUpdatedAt()).isAfter(atCreation);
		assertThat(userRepository.count()).isEqualTo(1);
	}

	@Test
	void getOrCreateByGoogle_shouldSyncProfile_whenEmailChangedAtTheIdP() {
		User first = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");

		User synced = userService.getOrCreateByGoogle(GOOGLE_ID, "tiago@example.org", "Tiago");
		userRepository.flush();
		entityManager.clear();

		assertThat(synced.getId()).isEqualTo(first.getId());
		assertThat(userRepository.findById(synced.getId()).orElseThrow().getEmail())
				.isEqualTo("tiago@example.org");
		assertThat(userRepository.count()).isEqualTo(1);
	}

	/**
	 * The OIDC name claim is optional; Google itself falls back to the email local part for such
	 * accounts, and so must sign-in, otherwise User.update's non-blank rule would be the thing
	 * rejecting a legitimate login.
	 */
	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void getOrCreateByGoogle_shouldFallBackToEmailLocalPart_whenNameClaimIsBlank(String name) {
		User user = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, name);

		assertThat(user.getName()).isEqualTo("tiago");
	}

	// --- Lookup -------------------------------------------------------------------------------

	@Test
	void getById_shouldReturnTheAccount_whenItExists() {
		User user = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");

		assertThat(userService.getById(user.getId()).getEmail()).isEqualTo(EMAIL);
	}

	/**
	 * An authenticated request carries an id that was valid when its token was issued, and the account
	 * may have been deleted since. Absence has to surface as an exception the controller layer can
	 * map, never as a null or a half-built user.
	 */
	@Test
	void getById_shouldReject_whenAccountDoesNotExist() {
		UUID phantom = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");

		assertThatThrownBy(() -> userService.getById(phantom))
				.isInstanceOf(UserNotFoundException.class);
	}

	// --- Deletion -----------------------------------------------------------------------------

	@Test
	void delete_shouldRemoveAccount_whenItOwnsNoFeeders() {
		User user = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");

		userService.delete(user.getId());
		userRepository.flush();

		assertThat(userRepository.findById(user.getId())).isEmpty();
	}

	/**
	 * The database RESTRICT on feeders - users is the real guard; this check exists so a future
	 * controller answers 409 instead of letting a constraint violation bubble up. The two would
	 * only disagree under a race, and then the database wins.
	 */
	@Test
	void delete_shouldReject_whenAccountStillOwnsFeeders() {
		User user = userService.getOrCreateByGoogle(GOOGLE_ID, EMAIL, "Tiago");
		feederRepository.saveAndFlush(Feeder.create(
				UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "Alimentador da sala", null, user));

		assertThatThrownBy(() -> userService.delete(user.getId()))
				.isInstanceOf(UserHasFeedersException.class);

		assertThat(userRepository.findById(user.getId())).isPresent();
	}

	@Test
	void delete_shouldReject_whenAccountDoesNotExist() {
		UUID phantom = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

		assertThatThrownBy(() -> userService.delete(phantom))
				.isInstanceOf(UserNotFoundException.class);
	}
}