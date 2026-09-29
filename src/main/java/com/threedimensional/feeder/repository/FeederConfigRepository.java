package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.FeederConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeederConfigRepository extends JpaRepository<FeederConfig, UUID> {
    /** Every rule of a feeder, active or paused. Use for management screens. */
    List<FeederConfig> findByFeederId(UUID feederId);

    /** The set a scheduler needs: rules that are currently switched on. */
    List<FeederConfig> findByFeederIdAndIsActiveTrue(UUID feederId);

    /**
     * Management screens showing a daily schedule in chronological order. Rows of type
     * INTERVAL have a null {@code timeOfDay} and therefore sort ahead of the timed rules.
     */
    List<FeederConfig> findByFeederIdOrderByTimeOfDayAsc(UUID feederId);

    /**
     * Duplicate checks before insert, so the caller gets a readable error instead of a raw
     * violation of {@code uk_feeder_configs_time}. This constraint deliberately ignores the
     * weekdays: two rules at the same time would double-dispense on any overlapping day.
     */
    Optional<FeederConfig> findByFeederIdAndTimeOfDay(UUID feederId, LocalTime timeOfDay);

    /** Duplicate check counterpart for {@code uk_feeder_configs_interval}. */
    Optional<FeederConfig> findByFeederIdAndIntervalMinutes(UUID feederId, Integer intervalMinutes);
}
