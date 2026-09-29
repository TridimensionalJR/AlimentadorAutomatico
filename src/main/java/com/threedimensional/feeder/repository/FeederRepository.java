package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.Feeder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeederRepository extends JpaRepository<Feeder, UUID> {
    /**
     * Every feeder registered to an account. Backed by {@code findByUserId} traversing the
     * {@code user_id} column of the {@code feeders} table, which is indexed as the foreign key.
     */
    List<Feeder> findByUserId(UUID userId);
}
