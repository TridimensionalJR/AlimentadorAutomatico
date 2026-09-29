package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, UUID> {
    /**
     * Looks an account up by the subject claim of its Google ID token. This is the query sign-in
     * runs with: Google is the identity provider, so there is no other identifier to search by.
     * Returns {@link Optional} because a first-time visitor has no account yet.
     */
    Optional<User> findByGoogleId(String googleId);
}
