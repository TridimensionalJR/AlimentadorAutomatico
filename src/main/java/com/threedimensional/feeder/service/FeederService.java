
package com.threedimensional.feeder.service;

import com.threedimensional.feeder.dto.FeederRequest;
import com.threedimensional.feeder.dto.FeederUpdateRequest;
import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.repository.FeederConfigRepository;
import com.threedimensional.feeder.repository.FeederRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class FeederService {

    private final FeederRepository feederRepository;
    private final FeederConfigRepository configRepository;
    private final UserService userService;

    public FeederService(
            FeederRepository feederRepository,
            FeederConfigRepository configRepository,
            UserService userService) {
        this.feederRepository = feederRepository;
        this.configRepository = configRepository;
        this.userService = userService;
    }

    // CREATE: registers a feeder for the authenticated user.
    @Transactional
    public Feeder create(UUID userId, FeederRequest request) {

        // Retrieves the user who will own the feeder.
        User user = userService.getById(userId);

        // Creates the entity using the received data.
        Feeder feeder = Feeder.create(
                request.deviceId(),
                request.name(),
                request.description(),
                user
        );

        // Sets the timezone only when a value is provided.
        if (request.timezone() != null
                && !request.timezone().isBlank()) {
            feeder.updateTimezone(request.timezone());
        }

        // Persists the feeder in the database.
        return feederRepository.save(feeder);
    }

    // READ: retrieves a feeder belonging to the user.
    @Transactional(readOnly = true)
    public Optional<Feeder> findById(UUID feederId, UUID userId) {
        return feederRepository.findByIdAndUserId(feederId, userId);
    }

    // READ: lists the user's feeders in pages of up to 50 items.
    @Transactional(readOnly = true)
    public Page<Feeder> findByPage(UUID userId, int page) {

        // Sorts from the most recently created feeder to the oldest.
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                50,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return feederRepository.findByUserId(userId, pageable);
    }

    // UPDATE: updates the editable feeder information.
    @Transactional
    public Optional<Feeder> update(
            UUID feederId,
            UUID userId,
            FeederUpdateRequest request) {

        // Finds the feeder only if it belongs to the authenticated user.
        return feederRepository.findByIdAndUserId(feederId, userId)
                .map(feeder -> {

                    // Updates the name and description.
                    feeder.update(
                            request.name(),
                            request.description()
                    );

                    // Updates the timezone if a value is provided.
                    if (request.timezone() != null
                            && !request.timezone().isBlank()) {
                        feeder.updateTimezone(request.timezone());
                    }

                    // Saves the changes.
                    return feederRepository.save(feeder);
                });
    }

    // DELETE: deletes the feeder and its configurations.
    @Transactional
    public boolean delete(UUID feederId, UUID userId) {

        // Checks whether the feeder belongs to the user.
        Optional<Feeder> existingFeeder =
                feederRepository.findByIdAndUserId(feederId, userId);

        // If it does not exist or belongs to another user, do not delete it.
        if (existingFeeder.isEmpty()) {
            return false;
        }

        // Deletes the feeder's dependent configurations first.
        configRepository.deleteAll(
                configRepository.findByFeederId(feederId)
        );

        // Then deletes the feeder itself.
        feederRepository.delete(existingFeeder.get());

        return true;
    }
}
