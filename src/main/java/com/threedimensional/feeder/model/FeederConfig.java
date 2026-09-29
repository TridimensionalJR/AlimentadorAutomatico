package com.threedimensional.feeder.model;

import com.threedimensional.feeder.model.enums.ConfigType;
import jakarta.persistence.*;
import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;

/**
 * A single feeding rule owned by a {@link Feeder}. A feeder may hold many of these.
 * <p>
 * There are two shapes of rule, discriminated by {@link #type}:
 * <ul>
 *   <li>{@link ConfigType#INTERVAL} - repeats forever, every {@code intervalMinutes}.
 *       {@code intervalMinutes} is set and {@code timeOfDay} is {@code null}.</li>
 *   <li>{@link ConfigType#DAILY_TIME} - fires at a fixed wall-clock time on selected weekdays.
 *       {@code timeOfDay} is set and {@code intervalMinutes} is {@code null}.</li>
 * </ul>
 * Exactly one of those two fields is therefore always null. This is not cosmetic: it is what
 * lets the database enforce one-time-per-slot uniqueness with two plain UNIQUE constraints,
 * see {@code V3__create_feeder_configs_table.sql}.
 * <p>
 * The class exposes no setters. Instances are built through {@link #createInterval} or
 * {@link #createDailyTime} and mutated only through the business methods below, each of which
 * re-runs validation. Every invariant is mirrored by a CHECK constraint in the migration, so a
 * row can never reach the database in an invalid shape regardless of the calling code.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Table(name = "feeder_configs", uniqueConstraints = {@UniqueConstraint(name = "uk_feeder_configs_time", columnNames = {"feeder_id", "time_of_day"}), @UniqueConstraint(name = "uk_feeder_configs_interval", columnNames = {"feeder_id", "interval_minutes"})})
public class FeederConfig {
    /**
     * One minute is the floor because a zero or negative interval would stall the scheduler -
     * it would never advance to a next run.
     */
    private static final int MIN_INTERVAL_MINUTES = 1;
    /** Seven days. Wide enough for any feeding pattern while still bounding runaway schedules. */
    private static final int MAX_INTERVAL_MINUTES = 10_080;
    private static final int MIN_DOSE = 1;
    /**
     * Reference point: the firmware offered at most 25 doses per trigger. 100 leaves room for
     * calibration without permitting a schedule that would physically jam the auger.
     */
    private static final int MAX_DOSE = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false)
    private ConfigType type;
    /** Elapsed time between runs. Only populated for {@link ConfigType#INTERVAL}. */
    @Column(name = "interval_minutes")
    private Integer intervalMinutes;
    /**
     * Wall-clock time with no date and no offset - a recurring pattern, not an event. Storing an
     * instant here would be wrong: it would need an arbitrary reference date, would freeze across
     * daylight-saving transitions, and would contradict {@link #weekdays}.
     * Only populated for {@link ConfigType#DAILY_TIME}, and resolved against the owning feeder's
     * {@link Feeder#zoneId()} when the next run is computed.
     */
    @Column(name = "time_of_day")
    private LocalTime timeOfDay;
    /**
     * Weekdays this rule runs on. Never null and never empty: {@link ConfigType#INTERVAL} is
     * forced to all seven because it repeats continuously, while {@link ConfigType#DAILY_TIME}
     * requires an explicit selection. Stored as {@code "MON,TUE,WED"}.
     */
    @Convert(converter = DayOfWeekSetConverter.class)
    @Column(name = "weekdays", nullable = false, length = 50)
    private Set<DayOfWeek> weekdays;
    /** How many times the motor turns per trigger, matching the firmware's dose counter. */
    @Column(name = "dose", nullable = false)
    private Integer dose;
    /**
     * Anchor for the next {@link ConfigType#INTERVAL} run. Null until the first execution.
     * The firmware anchors the interval on the moment the system is switched on; persisting it
     * here is what lets an interval survive a backend restart instead of losing its count and
     * firing twice, or far too late.
     */
    @Column(name = "last_run_at")
    private Instant lastRunAt;
    /** Allows pausing one rule without deleting it, independently of the feeder. */
    @Column(name = "is_active", nullable = false)
    private Boolean isActive;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feeder_id", nullable = false)
    private Feeder feeder;
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    /** Required by JPA. Leaves fields null, so persistence must never use it to build state. */
    protected FeederConfig() {
    }

    /**
     * Single constructor for both shapes. The two mutually exclusive fields are assigned from a
     * conditional, which nulls out the one that does not belong to {@code type}. That makes an
     * inconsistent rule unrepresentable rather than merely discouraged.
     */
    private FeederConfig(Feeder feeder, ConfigType type, Integer intervalMinutes, LocalTime timeOfDay,
                         Set<DayOfWeek> weekdays, int dose) {
        this.feeder = validateFeeder(feeder);
        this.type = validateType(type);
        this.intervalMinutes = type == ConfigType.INTERVAL ? validateIntervalMinutes(intervalMinutes) : null;
        this.timeOfDay = type == ConfigType.DAILY_TIME ? validateTimeOfDay(timeOfDay) : null;
        this.weekdays = type == ConfigType.INTERVAL ? allWeekdays() : validateWeekdays(weekdays);
        this.dose = validateDose(dose);
        this.isActive = true;
    }

    /**
     * Creates a rule that repeats every {@code intervalMinutes}, all week, all day.
     * The caller never passes a time or weekdays - both are meaningless for this shape.
     */
    public static FeederConfig createInterval(Feeder feeder, int intervalMinutes, int dose) {
        return new FeederConfig(feeder, ConfigType.INTERVAL, intervalMinutes, null, null, dose);
    }

    /** Creates a rule that fires at {@code timeOfDay} on the given weekdays. */
    public static FeederConfig createDailyTime(Feeder feeder, LocalTime timeOfDay, Set<DayOfWeek> weekdays, int dose) {
        return new FeederConfig(feeder, ConfigType.DAILY_TIME, null, timeOfDay, weekdays, dose);
    }

    /**
     * Replaces every mutable scheduling attribute. Switching {@code type} is allowed: the same
     * conditionals that guard the constructor run again here, so changing an interval into a
     * daily time clears the stale {@code intervalMinutes} instead of leaving both set.
     * The timezone is not touched - it belongs to the feeder, which keeps its location.
     */
    public void update(ConfigType type, Integer intervalMinutes, LocalTime timeOfDay,
                       Set<DayOfWeek> weekdays, int dose) {
        this.type = validateType(type);
        this.intervalMinutes = type == ConfigType.INTERVAL ? validateIntervalMinutes(intervalMinutes) : null;
        this.timeOfDay = type == ConfigType.DAILY_TIME ? validateTimeOfDay(timeOfDay) : null;
        this.weekdays = type == ConfigType.INTERVAL ? allWeekdays() : validateWeekdays(weekdays);
        this.dose = validateDose(dose);
    }

    /**
     * Records that the rule fired. The instant is passed in rather than read from
     * {@code Instant.now()} so scheduling stays deterministic and testable.
     */
    public void registerRun(Instant ranAt) {
        this.lastRunAt = validateRanAt(ranAt);
    }

    /** The single place weekday rules live: true when active and the date matches the selection. */
    public boolean isDueOn(LocalDate date) {
        return Boolean.TRUE.equals(isActive) && weekdays.contains(date.getDayOfWeek());
    }

    public void activate() {
        this.isActive = true;
    }

    public void deactivate() {
        this.isActive = false;
    }

    private static Feeder validateFeeder(Feeder feeder) {
        if (feeder == null) {
            throw new IllegalArgumentException("feeder must not be null");
        }
        return feeder;
    }

    private static ConfigType validateType(ConfigType type) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        return type;
    }

    private static int validateIntervalMinutes(Integer intervalMinutes) {
        if (intervalMinutes == null) {
            throw new IllegalArgumentException("interval minutes must not be null");
        }
        if (intervalMinutes < MIN_INTERVAL_MINUTES || intervalMinutes > MAX_INTERVAL_MINUTES) {
            throw new IllegalArgumentException(
                    "interval minutes must be between " + MIN_INTERVAL_MINUTES + " and " + MAX_INTERVAL_MINUTES);
        }
        return intervalMinutes;
    }

    private static LocalTime validateTimeOfDay(LocalTime timeOfDay) {
        if (timeOfDay == null) {
            throw new IllegalArgumentException("time of day must not be null");
        }
        return timeOfDay;
    }

    private static Set<DayOfWeek> validateWeekdays(Set<DayOfWeek> weekdays) {
        if (weekdays == null || weekdays.isEmpty()) {
            throw new IllegalArgumentException("weekdays must not be empty");
        }
        if (weekdays.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("weekdays must not contain null");
        }
        // Copied into an EnumSet so the stored collection is immutable to the caller, who would
        // otherwise be able to bypass validation by mutating the set they passed in.
        return Collections.unmodifiableSet(EnumSet.copyOf(weekdays));
    }

    private static int validateDose(int dose) {
        if (dose < MIN_DOSE || dose > MAX_DOSE) {
            throw new IllegalArgumentException("dose must be between " + MIN_DOSE + " and " + MAX_DOSE);
        }
        return dose;
    }

    private static Instant validateRanAt(Instant ranAt) {
        if (ranAt == null) {
            throw new IllegalArgumentException("run moment must not be null");
        }
        return ranAt;
    }

    /** Used for {@link ConfigType#INTERVAL}, which repeats continuously with no day selection. */
    private static Set<DayOfWeek> allWeekdays() {
        return Collections.unmodifiableSet(EnumSet.allOf(DayOfWeek.class));
    }
}
