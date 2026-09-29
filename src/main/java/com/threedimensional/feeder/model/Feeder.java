package com.threedimensional.feeder.model;

import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

/**
 * A physical automatic feeder, paired to a hardware device by {@link #deviceId} and owned by
 * exactly one {@link User}. Holds the rules that drive it, one {@link FeederConfig} per schedule.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Table(name = "feeders", uniqueConstraints = {@UniqueConstraint(name = "uk_feeders_device_id", columnNames = "device_id")})
public class Feeder {
    /**
     * Applied to newly created feeders. Brazil spans four zones, so any feeder outside Salvador
     * must override it through {@link #updateTimezone(String)}.
     */
    private static final String DEFAULT_TIMEZONE = "America/Bahia";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    /**
     * Identifier reported by the hardware. Unique across the whole table, so one physical device
     * can only ever be paired with one feeder.
     */
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;
    @Column(name = "name", nullable = false)
    private String name;
    @Column(name = "description")
    private String description;
    /**
     * IANA zone id (for example {@code America/Manaus}) that resolves the wall-clock times of
     * this feeder's rules into real instants.
     * <p>
     * Held here rather than on {@link FeederConfig} on purpose. A feeder is one physical place,
     * so one value covers all of its rules: it cannot be forgotten on a single rule, and moving
     * the device elsewhere is a single field update instead of rewriting every schedule it owns.
     */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;
    /** Switches the whole feeder on or off, independently of its individual rules. */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
    /**
     * The owning account. Lazy because the owner is almost never needed when scheduling, and
     * {@code spring.jpa.open-in-view=false} means an unloaded association here raises
     * LazyInitializationException rather than silently querying outside the transaction.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Required by JPA. Leaves fields null, so persistence must never use it to build state. */
    protected Feeder() {
    }

    private Feeder(UUID deviceId, String name, String description, User user) {
        this.deviceId = validateDeviceId(deviceId);
        this.name = validateName(name);
        this.description = description;
        this.timezone = DEFAULT_TIMEZONE;
        this.user = validateUser(user);
        this.isActive = true;
    }

    public static Feeder create(UUID deviceId, String name, String description, User user) {
        return new Feeder(deviceId, name, description, user);
    }

    /**
     * Applies a rename. The relation to {@link User} and {@link #deviceId} have no update method
     * on purpose: an owner and a hardware pairing are reassigned only by creating a new feeder,
     * never by mutating an existing one.
     */
    public void update(String name, String description) {
        this.name = validateName(name);
        this.description = description;
    }

    /** Moves the device to another IANA zone, for feeders outside Salvador. */
    public void updateTimezone(String timezone) {
        this.timezone = validateTimezone(timezone);
    }

    /** The parsed zone, so callers do not each repeat the {@code ZoneId.of} conversion. */
    public ZoneId zoneId() {
        return ZoneId.of(timezone);
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

    /**
     * Rejects unknown zone ids here rather than at scheduling time, because a typo would
     * otherwise surface much later as every rule of this feeder silently firing in the JVM's
     * default zone.
     */
    private static String validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            throw new IllegalArgumentException("timezone must not be blank");
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("timezone must be a valid zone id: " + timezone);
        }
        return timezone;
    }
}
