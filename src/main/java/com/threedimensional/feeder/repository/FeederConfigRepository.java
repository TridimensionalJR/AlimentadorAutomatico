package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.FeederConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * Management screens showing a daily schedule in chronological order. Rows of type INTERVAL
     * have a null {@code timeOfDay} and therefore sort ahead of the timed rules, a promise the
     * ordering has to spell out: H2 defaults NULLS FIRST for an ascending sort but PostgreSQL
     * defaults NULLS LAST, so a bare ORDER BY would return the opposite orders on the two
     * databases. Spring Data has no derived keyword for it, hence the explicit query, whose NULLS
     * FIRST clause is valid on both.
     */
    @Query("select c from FeederConfig c where c.feeder.id = :feederId order by c.timeOfDay asc nulls first")
    List<FeederConfig> findByFeederIdOrderByTimeOfDayAscNullsFirst(@Param("feederId") UUID feederId);

    /**
     * Duplicate checks before insert, so the caller gets a readable error instead of a raw
     * violation of {@code uk_feeder_configs_time}. This constraint deliberately ignores the
     * weekdays: two rules at the same time would double-dispense on any overlapping day.
     */
    Optional<FeederConfig> findByFeederIdAndTimeOfDay(UUID feederId, LocalTime timeOfDay);

    /** Duplicate check counterpart for {@code uk_feeder_configs_interval}. */
    Optional<FeederConfig> findByFeederIdAndIntervalMinutes(UUID feederId, Integer intervalMinutes);
}
