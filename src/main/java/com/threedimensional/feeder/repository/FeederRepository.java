package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.Feeder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;




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


    // Busca um alimentador pelo ID e pelo ID do proprietário.
    Optional<Feeder> findByIdAndUserId(UUID feederId, UUID userId);

    // Lista os alimentadores com paginação.
    Page<Feeder> findByUserId(UUID userId, Pageable pageable);

}
