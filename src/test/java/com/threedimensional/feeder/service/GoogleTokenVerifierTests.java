package com.threedimensional.feeder.service;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.threedimensional.feeder.config.JwtConfig;
import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The trust boundary of sign-in. Google is never contacted: the test signs tokens with a key pair
 * it generates and gives the decoder the matching public key, which makes the verifier see exactly
 * what it would see from Google while every claim stays under the test's control.
 * <p>
 * The decoder is wired with {@link JwtConfig#googleTokenValidator}, the very rule set production
 * uses, so what passes here is what passes there. Only the source of the keys differs.
 */
class GoogleTokenVerifierTests {

	private static final String CLIENT_ID = "feeder-client.apps.googleusercontent.com";
	private static final String GOOGLE_ID = "google-abc";
	private static final String EMAIL = "tiago@example.com";

	private static RSAPrivateKey privateKey;
	private static RSAPublicKey publicKey;

	private GoogleTokenVerifier verifier;

	@BeforeAll
	static void generateKeys() throws Exception {
		KeyPair pair = newKeyPair();
		privateKey = (RSAPrivateKey) pair.getPrivate();
		publicKey = (RSAPublicKey) pair.getPublic();
	}

	@BeforeEach
	void setUp() {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
		decoder.setJwtValidator(JwtConfig.googleTokenValidator(CLIENT_ID));
		verifier = new GoogleTokenVerifier(decoder);
	}

	// --- Accepted -----------------------------------------------------------------------------

	@Test
	void verify_shouldReturnTheIdentity_whenTokenIsValid() throws Exception {
		String token = sign(validClaims(), privateKey);

		GoogleIdentity identity = verifier.verify(token);

		assertThat(identity.googleId()).isEqualTo(GOOGLE_ID);
		assertThat(identity.email()).isEqualTo(EMAIL);
		assertThat(identity.name()).isEqualTo("Tiago");
	}

	/** Google documents both spellings of its issuer and sends either, so both must be accepted. */
	@Test
	void verify_shouldAcceptTheBareIssuerSpelling() throws Exception {
		String token = sign(validClaims().issuer("accounts.google.com"), privateKey);

		assertThat(verifier.verify(token).googleId()).isEqualTo(GOOGLE_ID);
	}

	/**
	 * The OIDC name claim is optional. The verifier passes the absence through as null and leaves
	 * the fallback to the user service, which already owns that rule.
	 */
	@Test
	void verify_shouldReturnNullName_whenTheClaimIsAbsent() throws Exception {
		String token = sign(baseClaims().claim("email", EMAIL).claim("email_verified", true), privateKey);

		assertThat(verifier.verify(token).name()).isNull();
	}

	// --- Rejected -----------------------------------------------------------------------------

	/** A token Google issued to some other application must not sign anyone in here. */
	@Test
	void verify_shouldReject_whenIssuedForAnotherClient() throws Exception {
		String token = sign(validClaims().audience("someone-elses-client-id"), privateKey);

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidGoogleTokenException.class);
	}

	@Test
	void verify_shouldReject_whenIssuerIsNotGoogle() throws Exception {
		String token = sign(validClaims().issuer("https://evil.example.com"), privateKey);

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidGoogleTokenException.class);
	}

	@Test
	void verify_shouldReject_whenTokenIsExpired() throws Exception {
		String token = sign(validClaims().expirationTime(Date.from(Instant.now().minusSeconds(3600))), privateKey);

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidGoogleTokenException.class);
	}

	/**
	 * The account is later shown and contacted by email, and Google does not vouch for an address
	 * it has not confirmed.
	 */
	@Test
	void verify_shouldReject_whenEmailIsNotVerified() throws Exception {
		String token = sign(validClaims().claim("email_verified", false), privateKey);

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidGoogleTokenException.class);
	}

	/** Happens when the front did not request the email scope. */
	@Test
	void verify_shouldReject_whenEmailClaimIsMissing() throws Exception {
		String token = sign(baseClaims().claim("email_verified", true), privateKey);

		assertThatThrownBy(() -> verifier.verify(token)).isInstanceOf(InvalidGoogleTokenException.class);
	}

	/** Correct claims are worthless if the signature is not Google's. */
	@Test
	void verify_shouldReject_whenSignedWithAnotherKey() throws Exception {
		RSAPrivateKey attackerKey = (RSAPrivateKey) newKeyPair().getPrivate();
		String forged = sign(validClaims(), attackerKey);

		assertThatThrownBy(() -> verifier.verify(forged)).isInstanceOf(InvalidGoogleTokenException.class);
	}

	@Test
	void verify_shouldReject_whenTokenIsMalformed() {
		assertThatThrownBy(() -> verifier.verify("not-a-jwt")).isInstanceOf(InvalidGoogleTokenException.class);
	}

	// --- Not the caller's fault ---------------------------------------------------------------

	/**
	 * A decoder that cannot fetch Google's keys fails with a plain {@code JwtException}, not a bad
	 * token. Reporting that as 401 would tell a legitimate user their credential is wrong when the
	 * outage is ours, so it must come out as the server error it is.
	 */
	@Test
	void verify_shouldNotReportInfrastructureFailures_asAnInvalidToken() {
		JwtDecoder unreachable = mock(JwtDecoder.class);
		when(unreachable.decode(anyString())).thenThrow(new JwtException("could not fetch the key set"));
		GoogleTokenVerifier verifierWithoutKeys = new GoogleTokenVerifier(unreachable);

		assertThatThrownBy(() -> verifierWithoutKeys.verify("any-token"))
				.isInstanceOf(JwtException.class)
				.isNotInstanceOf(InvalidGoogleTokenException.class);
	}

	// --- Helpers ------------------------------------------------------------------------------

	/** Everything a token needs except the account's own claims. */
	private static JWTClaimsSet.Builder baseClaims() {
		return new JWTClaimsSet.Builder()
				.issuer("https://accounts.google.com")
				.audience(CLIENT_ID)
				.subject(GOOGLE_ID)
				.expirationTime(Date.from(Instant.now().plusSeconds(3600)));
	}

	private static JWTClaimsSet.Builder validClaims() {
		return baseClaims()
				.claim("email", EMAIL)
				.claim("email_verified", true)
				.claim("name", "Tiago");
	}

	private static String sign(JWTClaimsSet.Builder claims, RSAPrivateKey key) throws JOSEException {
		JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build();
		SignedJWT jwt = new SignedJWT(header, claims.build());
		jwt.sign(new RSASSASigner(key));
		return jwt.serialize();
	}

	private static KeyPair newKeyPair() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}
}
