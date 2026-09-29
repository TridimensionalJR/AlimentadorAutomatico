package com.threedimensional.feeder.model;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * An account holder, authenticated through Google OAuth. Identified by {@code googleId} from the
 * token subject, and owns the {@link Feeder} instances registered to their account.
 * <p>
 * Unlike {@link Feeder}, {@link #create} performs no validation. Its arguments come from a
 * verified Google ID token, where {@code sub} and {@code email} are guaranteed present and
 * well-formed, so re-checking them would be noise. {@link #update} does validate, because those
 * values can originate from user input once an authenticated session already exists.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Table(name = "users", uniqueConstraints = {@UniqueConstraint(name = "uk_users_email", columnNames = {"email"}), @UniqueConstraint(name = "uk_users_google_id", columnNames = {"google_id"})})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    /**
     * Subject claim of the Google ID token. This, not the id, is the stable natural key used to
     * find an account again on the next sign-in.
     */
    @Column(name = "google_id", nullable = false)
    private String googleId;
    @Column(name = "email", nullable = false)
    private String email;
    /**
     * Display name. Note that the OIDC {@code name} claim is optional, so a Google account may
     * supply none; callers are expected to fall back to the email local part, as Google does.
     * {@link #update} rejects a blank value, which is why sign-in must never pass one through.
     */
    @Column(name = "name", nullable = false)
    private String name;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Required by JPA. Leaves fields null, so persistence must never use it to build state. */
    protected User() {
    }

    private User(String googleId, String email, String name) {
        this.googleId = googleId;
        this.email = email;
        this.name = name;
    }

    /** Builds an account from verified Google token claims. See the class note on validation. */
    public static User create(String googleId, String email, String name) {
        return new User(googleId, email, name);
    }

    /**
     * Applies profile changes. Both fields are re-validated because, unlike at sign-in, these can
     * come from user input rather than from Google. {@code googleId} is immutable and has no
     * method of its own: the identity provider owns it and it must never be reassigned.
     */
    public void update(String email, String name) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        this.email = email;
        this.name = name;
    }
}
