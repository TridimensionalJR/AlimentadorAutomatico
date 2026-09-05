package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.Feeder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FeederRepository extends JpaRepository<Feeder, UUID> {
    List<Feeder> findByUserId(UUID userId);
}
