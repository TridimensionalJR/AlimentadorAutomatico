package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.Feeder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FeederRepository extends JpaRepository<Feeder, UUID> {
    /**
     * Every feeder registered to an account, matching on the {@code user_id} column of the
     * {@code feeders} table.
     */
    List<Feeder> findByUserId(UUID userId);

    /**
     * Account deletion's guard: whether any feeder still belongs to the account. Running as an
     * EXISTS query, it never loads rows just to answer yes or no.
     */
    boolean existsByUserId(UUID userId);

    /**
     * Every owner-scoped read and write starts here: filtering by {@code user.id} in the query
     * means a feeder of another account simply does not exist, so the service reports one uniform
     * 404 without a second ownership check to forget.
     */
    Optional<Feeder> findByIdAndUserId(UUID id, UUID userId);

    /**
     * Pre-check before pairing a device, so the common duplicate gets a readable 409 instead of a
     * raw {@code uk_feeders_device_id} violation. Exists because it answers yes/no without
     * loading the row; {@code findByDeviceId(...).isPresent()} would fetch an entity for this.
     * The UNIQUE constraint remains the authority — this check can race.
     */
    boolean existsByDeviceId(UUID deviceId);
}
