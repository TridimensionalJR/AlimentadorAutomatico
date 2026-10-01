package com.threedimensional.feeder.service;

import com.threedimensional.feeder.config.AuthProperties;
import com.threedimensional.feeder.model.User;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Issues the tokens that authenticate a user against this API. Verifying them is the job of the
 * resource server filter, configured in {@code SecurityConfig}, so this class only ever writes.
 * <p>
 * The token identifies the account and nothing else: subject, issuer and the two timestamps. No
 * email and no name, because those are profile data that can change under a token that stays valid
 * for an hour, and because a JWT is readable by whoever holds it, the browser's scripts included.
 */
@Service
public class JwtService {

    private final JwtEncoder jwtEncoder;

    private final AuthProperties properties;

    private final Clock clock;

    public JwtService(JwtEncoder jwtEncoder, AuthProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The subject is the internal {@code User.id}, not the Google {@code sub}: the identity
     * provider's key stays on this side of the API, and the rest of the application, which only
     * ever knows users by id, can read the principal straight off the token.
     */
    public AccessToken issue(User user) {
        Instant issuedAt = clock.instant();
        Duration lifetime = properties.jwt().expiration();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.jwt().issuer())
                .subject(user.getId().toString())
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(lifetime))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();

        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new AccessToken(value, lifetime);
    }

    /**
     * A freshly issued token. The lifetime travels with it, rather than being left for the caller
     * to read back from configuration, so the response a client receives always matches the
     * {@code exp} actually written into the token.
     */
    public record AccessToken(String value, Duration expiresIn) {
    }
}
