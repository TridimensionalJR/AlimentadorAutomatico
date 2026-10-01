package com.threedimensional.feeder.service;

import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Service;

/**
 * The trust boundary of sign-in. Everything past this class, {@code User.create} included, assumes
 * its inputs came from a token Google really issued for this application, so nothing weaker than a
 * full verification belongs here.
 * <p>
 * The decoder checks the signature against Google's keys, the expiry, the issuer and the audience
 * (see {@code JwtConfig#googleTokenValidator}). What it cannot know is Google's own account rules,
 * which are checked here: the email must be verified, and {@code sub} and {@code email} must exist.
 * An unverified email is rejected because the account is later found again by {@code sub} but shown
 * and contacted by email, and Google does not vouch for an address it has not confirmed.
 */
@Service
public class GoogleTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleTokenVerifier.class);

    private final JwtDecoder googleJwtDecoder;

    /** Looked up by name: the application's own decoder is the {@code @Primary} one. */
    public GoogleTokenVerifier(@Qualifier("googleJwtDecoder") JwtDecoder googleJwtDecoder) {
        this.googleJwtDecoder = googleJwtDecoder;
    }

    /**
     * @throws InvalidGoogleTokenException when the token is not one Google issued for this
     *                                     application, or describes an account that cannot sign in
     */
    public GoogleIdentity verify(String idToken) {
        Jwt jwt = decode(idToken);

        if (!Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified"))) {
            throw rejected("the Google account's email is not verified");
        }
        String googleId = jwt.getSubject();
        String email = jwt.getClaimAsString("email");
        if (googleId == null || googleId.isBlank() || email == null || email.isBlank()) {
            throw rejected("the token carries no subject or email");
        }
        return new GoogleIdentity(googleId, email, jwt.getClaimAsString("name"));
    }

    /**
     * Only a {@link BadJwtException} means the token itself is at fault. Any other
     * {@code JwtException} is the decoder failing to do its job, typically because Google's keys
     * could not be fetched. That is our problem and not the caller's, so it is allowed to propagate
     * as a server error instead of being reported as a rejected credential.
     */
    private Jwt decode(String idToken) {
        try {
            return googleJwtDecoder.decode(idToken);
        } catch (BadJwtException e) {
            throw rejected(e.getMessage(), e);
        }
    }

    /**
     * The reason goes to the log and never to the response. The token itself is never logged: it
     * is a credential, and a valid one would let anyone who reads the log sign in as that person.
     */
    private InvalidGoogleTokenException rejected(String reason) {
        return rejected(reason, null);
    }

    private InvalidGoogleTokenException rejected(String reason, Throwable cause) {
        log.warn("Rejected Google ID token: {}", reason);
        return new InvalidGoogleTokenException("Google ID token was rejected: " + reason, cause);
    }
}
