package com.threedimensional.feeder.model;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Table(name = "users", uniqueConstraints = {@UniqueConstraint(name = "uk_users_email", columnNames = {"email"}), @UniqueConstraint(name = "uk_users_google_id", columnNames = {"google_id"})})
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "google_id", nullable = false)
    private String googleId;
    @Column(name = "email", nullable = false)
    private String email;
    @Column(name = "name", nullable = false)
    private String name;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected User() {
    }

    private User(String googleId, String email, String name) {
        this.googleId = googleId;
        this.email = email;
        this.name = name;
    }

    public static User create(String googleId, String email, String name) {
        return new User(googleId, email, name);
    }

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
