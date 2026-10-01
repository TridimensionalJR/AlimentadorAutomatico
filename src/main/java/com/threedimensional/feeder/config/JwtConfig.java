package com.threedimensional.feeder.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

/**
 * The two kinds of token this application handles, and the machinery for each.
 * <p>
 * <b>Application tokens</b> are signed by this API with HS256 and are what protects every endpoint
 * but sign-in. {@link #jwtEncoder} issues them and {@link #appJwtDecoder} verifies them.
 * <p>
 * <b>Google ID tokens</b> are signed by Google and are accepted at exactly one place, the sign-in
 * exchange, through {@link #googleJwtDecoder}. The two decoders are deliberately never
 * interchangeable: if the Google one ever guarded the API, any Google account would be
 * authenticated. That is why {@link #appJwtDecoder} is {@code @Primary}, so that anything resolving
 * a {@link JwtDecoder} by type gets the safe one, and why the Google one is looked up by name.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    /** The public keys Google signs ID tokens with. Fetched lazily and cached by the decoder. */
    private static final String GOOGLE_JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    /**
     * Google documents both spellings of its issuer, so both are valid. The stock issuer validator
     * takes a single value and would reject half of the tokens Google actually sends.
     */
    private static final List<String> GOOGLE_ISSUERS = List.of("https://accounts.google.com", "accounts.google.com");

    /**
     * A bean so token lifetimes are computed against an injectable time source, the same reason
     * {@code FeederConfig.registerRun} takes its instant as an argument: tests stay deterministic.
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public JwtEncoder jwtEncoder(AuthProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(signingKey(properties)));
    }

    /**
     * Checks signature and expiry, and that the issuer is this API. Without the issuer check any
     * token signed with the same key would pass, whatever it was minted for.
     */
    @Bean
    @Primary
    public JwtDecoder appJwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(signingKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.jwt().issuer()));
        return decoder;
    }

    /**
     * Verifies tokens against Google's published keys. Building it makes no network call: the keys
     * are fetched on the first token and then cached, so startup and the test suite never depend on
     * reaching Google.
     */
    @Bean
    public JwtDecoder googleJwtDecoder(AuthProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(GOOGLE_JWK_SET_URI).build();
        decoder.setJwtValidator(googleTokenValidator(properties.google().clientId()));
        return decoder;
    }

    /**
     * Everything a Google ID token must satisfy beyond a valid signature. Public and static so the
     * tests can run their own keys through the exact same rules instead of a copy of them.
     * <p>
     * Replacing the decoder's validator drops the default expiry check along with it, hence the
     * timestamp validator being listed again.
     */
    public static OAuth2TokenValidator<Jwt> googleTokenValidator(String clientId) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                issuerIsGoogle(),
                audienceIs(clientId));
    }

    private static OAuth2TokenValidator<Jwt> issuerIsGoogle() {
        return jwt -> {
            // Read as a string on purpose: "accounts.google.com" is not a URL, and the typed
            // getIssuer() accessor would try to parse it as one.
            String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
            return issuer != null && GOOGLE_ISSUERS.contains(issuer)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("token was not issued by Google");
        };
    }

    private static OAuth2TokenValidator<Jwt> audienceIs(String clientId) {
        return jwt -> {
            List<String> audience = jwt.getAudience();
            return audience != null && audience.contains(clientId)
                    ? OAuth2TokenValidatorResult.success()
                    : failure("token was issued for a different client");
        };
    }

    private static OAuth2TokenValidatorResult failure(String description) {
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(OAuth2ErrorCodes.INVALID_TOKEN, description, null));
    }

    private static SecretKey signingKey(AuthProperties properties) {
        return new SecretKeySpec(properties.jwt().secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
}
