package com.threedimensional.feeder.service;

import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import com.threedimensional.feeder.model.User;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The sign-in exchange: a Google ID token in, an application token out. It only orchestrates.
 * Verifying the token is {@link GoogleTokenVerifier}'s job, the account lifecycle stays in
 * {@link UserService}, whose {@code getOrCreateByGoogle} is the login path itself, and minting the
 * token is {@link JwtService}'s.
 * <p>
 * Not {@code @Transactional}, on purpose. Verification may reach out to Google for its keys, and a
 * database transaction must not stay open across a network call. The only transactional step is the
 * one inside {@code getOrCreateByGoogle}, which opens and commits its own.
 */
@Service
public class AuthService {

    private final GoogleTokenVerifier googleTokenVerifier;

    private final UserService userService;

    private final JwtService jwtService;

    public AuthService(GoogleTokenVerifier googleTokenVerifier, UserService userService, JwtService jwtService) {
        this.googleTokenVerifier = googleTokenVerifier;
        this.userService = userService;
        this.jwtService = jwtService;
    }

    /**
     * New and returning users take the same path and get the same answer, so the response never
     * reveals whether an account already existed.
     *
     * @throws InvalidGoogleTokenException when the token cannot be trusted, in which case no
     *                                     account is created or touched
     */
    public LoginResult loginWithGoogle(String idToken) {
        GoogleIdentity identity = googleTokenVerifier.verify(idToken);
        User user = getOrCreate(identity);
        return new LoginResult(user, jwtService.issue(user));
    }

    /**
     * {@link UserService} leaves a concurrent first login to the UNIQUE constraint and does not
     * catch the loser, which was a fair call while nothing could produce that race. A web front
     * can: a double click or a re-rendered component sends the same credential twice, and both
     * requests find no account. One of the two inserts then violates {@code uk_users_google_id} at
     * commit.
     * <p>
     * Retrying once is enough, because by then the winner's row is committed and the second attempt
     * simply finds it. It lives here rather than in {@code UserService} so that service stays as it
     * is. A second failure is not a race any more, for example the email already belongs to another
     * account, and propagates as the manual-reconciliation signal {@code UserService} describes.
     */
    private User getOrCreate(GoogleIdentity identity) {
        try {
            return userService.getOrCreateByGoogle(identity.googleId(), identity.email(), identity.name());
        } catch (DataIntegrityViolationException e) {
            return userService.getOrCreateByGoogle(identity.googleId(), identity.email(), identity.name());
        }
    }

    /** The account that signed in and the token that now represents it. */
    public record LoginResult(User user, JwtService.AccessToken accessToken) {
    }
}
