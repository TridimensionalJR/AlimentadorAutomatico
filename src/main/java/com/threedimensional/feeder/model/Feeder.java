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
@Table(name = "feeders", uniqueConstraints = {@UniqueConstraint(name = "uk_feeders_device_id", columnNames = "device_id")})
public class Feeder {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;
    @Column(name = "name", nullable = false)
    private String name;
    @Column(name = "description")
    private String description;
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Feeder() {
    }

    private Feeder(UUID deviceId, String name, String description, User user) {
        this.deviceId = validateDeviceId(deviceId);
        this.name = validateName(name);
        this.description = description;
        this.user = validateUser(user);
        this.isActive = true;
    }

    public static Feeder create(UUID deviceId, String name, String description, User user) {
        return new Feeder(deviceId, name, description, user);
    }

    public void update(String name, String description) {
        this.name = validateName(name);
        this.description = description;
    }

    public void activate() {
        this.isActive = true;
    }

    public void deactivate() {
        this.isActive = false;
    }

    private static UUID validateDeviceId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("device id must not be null");
        }
        return id;
    }

    private static String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return name;
    }

    private static User validateUser(User user) {
        if (user == null) {
            throw new IllegalArgumentException("user must not be null");
        }
        return user;
    }
}
