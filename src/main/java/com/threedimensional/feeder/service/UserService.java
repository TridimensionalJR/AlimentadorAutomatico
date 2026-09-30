package com.threedimensional.feeder.service;

import com.threedimensional.feeder.exception.UserHasFeedersException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.repository.FeederRepository;
import com.threedimensional.feeder.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * The account lifecycle. Google OAuth is the only way an account comes into existence, so there
 * is no create or edit controller: {@link #getOrCreateByGoogle} is the login path itself, and a
 * user's own profile fields are owned by the identity provider, never typed in.
 * <p>
 * Both flows treat the database as the last word on the write-path races: a second concurrent
 * first login for the same google_id, or a feeder inserted between the guard and the delete.
 * Those lose against the UNIQUE constraints and the RESTRICT, and catching them in code would
 * buy nothing while the app has a handful of accounts.
 */
@Service
public class UserService {

    private final UserRepository userRepository;

    private final FeederRepository feederRepository;

    public UserService(UserRepository userRepository, FeederRepository feederRepository) {
        this.userRepository = userRepository;
        this.feederRepository = feederRepository;
    }

    /**
     * Sign-in handler. The token subject is the account key; email and name are just profile data
     * Google can change, so every login re-applies whatever the latest token holds - an existing
     * account is synced in place, never duplicated. The write is skipped when nothing changed,
     * keeping {@code updated_at} meaning "profile changed" rather than "logged in again".
     *
     * @param googleId the token {@code sub} claim, the stable natural key of the account
     * @param email    the token {@code email} claim
     * @param name     the token {@code name} claim, blank when the Google account holds none
     * <p>
     * A sync that moves the email onto another account's row is deliberately not defended here:
     * Google emails are globally unique, so such a collision means an account reassignment, and
     * the UNIQUE constraint stops it at commit - a signal for manual reconciliation, not a code
     * path.
     */
    @Transactional
    public User getOrCreateByGoogle(String googleId, String email, String name) {
        String displayName = displayName(email, name);
        return userRepository.findByGoogleId(googleId)
                .map(existing -> {
                    if (!existing.getEmail().equals(email) || !existing.getName().equals(displayName)) {
                        existing.update(email, displayName);
                    }
                    return existing;
                })
                .orElseGet(() -> userRepository.save(User.create(googleId, email, displayName)));
    }

    /**
     * Removes an account. Blocked while the user still owns feeders: the {@code RESTRICT} on
     * feeders - users is the last line of defence, this check answers 409 in the common case.
     * Only the database wins when the two ever disagree.
     */
    @Transactional
    public void delete(UUID id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException(id));
        if (feederRepository.existsByUserId(id)) {
            throw new UserHasFeedersException(id);
        }
        userRepository.delete(user);
    }

    /**
     * The OIDC {@code name} claim is optional, so a blank one falls back to the email local part,
     * the name Google itself shows for such an account. Normalising it here is what keeps the one
     * path where nobody typed anything from tripping {@code User.update}'s non-blank rule.
     */
    private static String displayName(String email, String name) {
        if (name != null && !name.isBlank()) {
            return name;
        }
        int at = email.indexOf('@');
        return at < 0 ? email : email.substring(0, at);
    }
}