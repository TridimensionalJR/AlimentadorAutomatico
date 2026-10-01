package com.threedimensional.feeder.service;

import com.threedimensional.feeder.config.AuthProperties;
import com.threedimensional.feeder.config.JwtConfig;
import com.threedimensional.feeder.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The tokens this API issues, checked by decoding them with the same decoder the filter chain uses.
 * Nothing here touches the database or needs a Spring context: the beans of {@link JwtConfig} are
 * plain factory methods, so the test builds the encoder and decoder from them directly and what is
 * under test is the real signing and verification, not a stand-in.
 */
class JwtServiceTests {

	private static final String SECRET = "unit-test-secret-with-more-than-32-characters";
	private static final String ISSUER = "feeder";
	private static final UUID USER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

	private final JwtConfig jwtConfig = new JwtConfig();

	private final AuthProperties properties = properties(SECRET, ISSUER);

	private final JwtDecoder decoder = jwtConfig.appJwtDecoder(properties);

	private final JwtService jwtService = serviceFor(properties, Clock.systemUTC());

	// --- Claims -------------------------------------------------------------------------------

	/**
	 * The subject is the internal id, never the Google sub: that is what lets the rest of the
	 * application know a user only by the key it owns.
	 */
	@Test
	void issue_shouldPutTheAccountIdInTheSubject() {
		Jwt jwt = decoder.decode(jwtService.issue(user()).value());

		assertThat(jwt.getSubject()).isEqualTo(USER_ID.toString());
		assertThat(jwt.getClaimAsString("iss")).isEqualTo(ISSUER);
	}

	/**
	 * Email and name can change while a token stays valid, and a JWT is readable by whoever holds
	 * it, so neither belongs inside.
	 */
	@Test
	void issue_shouldCarryNoProfileData() {
		Jwt jwt = decoder.decode(jwtService.issue(user()).value());

		assertThat(jwt.getClaims()).doesNotContainKeys("email", "name", "google_id");
	}

	/**
	 * The lifetime reported to the client and the one written into the token come from the same
	 * place, so the {@code expiresIn} of the response can never drift from the real {@code exp}.
	 */
	@Test
	void issue_shouldExpireAfterTheConfiguredLifetime() {
		JwtService.AccessToken token = jwtService.issue(user());
		Jwt jwt = decoder.decode(token.value());

		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(1));
		assertThat(token.expiresIn()).isEqualTo(Duration.ofHours(1));
	}

	// --- What the decoder must refuse ---------------------------------------------------------

	@Test
	void decode_shouldRejectAToken_signedWithAnotherKey() {
		AuthProperties foreign = properties("another-secret-that-is-also-long-enough-0123", ISSUER);
		String forged = serviceFor(foreign, Clock.systemUTC()).issue(user()).value();

		assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
	}

	/**
	 * A token signed with the right key but minted for something else must still fail: the issuer
	 * is what says this API issued it.
	 */
	@Test
	void decode_shouldRejectAToken_fromAnotherIssuer() {
		String other = serviceFor(properties(SECRET, "someone-else"), Clock.systemUTC()).issue(user()).value();

		assertThatThrownBy(() -> decoder.decode(other)).isInstanceOf(JwtException.class);
	}

	/**
	 * Issued against a clock set years back, so the token is already past its lifetime by the time
	 * the decoder compares it with the real one.
	 */
	@Test
	void decode_shouldRejectAnExpiredToken() {
		Clock longAgo = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
		String expired = serviceFor(properties, longAgo).issue(user()).value();

		assertThatThrownBy(() -> decoder.decode(expired)).isInstanceOf(JwtException.class);
	}

	// --- Helpers ------------------------------------------------------------------------------

	private JwtService serviceFor(AuthProperties props, Clock clock) {
		return new JwtService(jwtConfig.jwtEncoder(props), props, clock);
	}

	private static AuthProperties properties(String secret, String issuer) {
		return new AuthProperties(
				new AuthProperties.Google("client-id"),
				new AuthProperties.Jwt(secret, issuer, Duration.ofHours(1)),
				new AuthProperties.Cors(List.of("http://localhost:5173")));
	}

	/** User has no setters and no id until persisted, so the id is placed by hand. */
	private static User user() {
		User user = User.create("google-abc", "tiago@example.com", "Tiago");
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}
}
