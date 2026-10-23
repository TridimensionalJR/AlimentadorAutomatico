package com.threedimensional.feeder.service;

import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.repository.FeederRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The feeder lifecycle. Every method takes the caller's id as its first parameter, and that is the
 * only owner this service knows: the controller reads it from the token subject, so a request has
 * no way to name somebody else's feeder.
 * <p>
 * Ownership is enforced in the query ({@link FeederRepository#findByIdAndUserId}) instead of being
 * checked in memory after loading a feeder. One filter for every read and write keeps a missing
 * feeder and another account's feeder the same {@link FeederNotFoundException}: one 404, no oracle
 * telling an attacker that such a feeder exists.
 * <p>
 * Methods return entities, as {@link UserService#getById} does, and the controller maps them to a
 * response. That is safe after this service's transaction ends because the response never reads the
 * lazy owner ({@code open-in-view=false}).
 */
@Service
public class FeederService {

    private final FeederRepository feederRepository;

    private final UserService userService;

    public FeederService(FeederRepository feederRepository, UserService userService) {
        this.feederRepository = feederRepository;
        this.userService = userService;
    }

    /**
     * Pairs a device with the caller's account. The account is loaded first on purpose: a token can
     * outlive the account it names, and registration is the one route where the user is
     * structurally required, so a deleted account answers its own 404 before the device is even
     * looked at.
     * <p>
     * {@code existsByDeviceId} only produces the readable 409 for the common duplicate; the UNIQUE
     * constraint remains the authority. Hence the flush: it forces the insert inside this method, so
     * the loser of a race fails at the one place the catch can see, instead of at commit after the
     * method has returned. A plain {@code save} would defer the insert and turn that 409 into a 500.
     * <p>
     * The catch covers the flush and nothing else. A blank name or an unknown zone is rejected by
     * {@link Feeder} as {@link IllegalArgumentException}, which the API answers with 400; folding
     * that into "device already registered" would report the wrong cause.
     *
     * @param timezone IANA zone id, or {@code null} to keep the domain default
     */
    @Transactional
    public Feeder create(UUID userId, UUID deviceId, String name, String description, String timezone) {
        User user = userService.getById(userId);
        if (feederRepository.existsByDeviceId(deviceId)) {
            throw new DeviceAlreadyRegisteredException(deviceId);
        }
        Feeder feeder = Feeder.create(deviceId, name, description, user);
        if (timezone != null) {
            feeder.updateTimezone(timezone);
        }
        try {
            return feederRepository.saveAndFlush(feeder);
        } catch (DataIntegrityViolationException e) {
            throw new DeviceAlreadyRegisteredException(deviceId);
        }
    }

    /**
     * The caller's feeders. An account that no longer exists gets an empty list rather than a 404:
     * the query filters by {@code user_id} and needs no account row to answer.
     */
    @Transactional(readOnly = true)
    public List<Feeder> list(UUID userId) {
        return feederRepository.findByUserId(userId);
    }

    /** One feeder of the caller. Another account's feeder answers exactly like a missing one. */
    @Transactional(readOnly = true)
    public Feeder get(UUID userId, UUID id) {
        return loadOwned(userId, id);
    }

    /**
     * Replaces the labels. There is no save call: the entity is managed inside this transaction, so
     * the dirty-checking flush writes the change at commit. Omitting {@code description} clears it,
     * matching {@link Feeder#update}.
     */
    @Transactional
    public Feeder update(UUID userId, UUID id, String name, String description) {
        Feeder feeder = loadOwned(userId, id);
        feeder.update(name, description);
        return feeder;
    }

    /**
     * Moves the device to another IANA zone. Validation stays in the domain, which keeps this method
     * from repeating the {@code ZoneId} rule; an unknown zone reaches the API as a 400.
     */
    @Transactional
    public Feeder updateTimezone(UUID userId, UUID id, String timezone) {
        Feeder feeder = loadOwned(userId, id);
        feeder.updateTimezone(timezone);
        return feeder;
    }

    /** Switches the feeder on. Idempotent: an active feeder just returns its current state. */
    @Transactional
    public Feeder activate(UUID userId, UUID id) {
        Feeder feeder = loadOwned(userId, id);
        feeder.activate();
        return feeder;
    }

    /** Switches the feeder off. Idempotent, mirroring {@link #activate}. */
    @Transactional
    public Feeder deactivate(UUID userId, UUID id) {
        Feeder feeder = loadOwned(userId, id);
        feeder.deactivate();
        return feeder;
    }

    /**
     * Removes one feeder of the caller. The entity is loaded first so a missing or foreign feeder is
     * the same 404; {@code deleteById} would throw {@code EmptyResultDataAccessException} and surface
     * as a 500. The schedules go with it through the FK's {@code ON DELETE CASCADE}.
     */
    @Transactional
    public void delete(UUID userId, UUID id) {
        feederRepository.delete(loadOwned(userId, id));
    }

    /**
     * The single lookup every owner-scoped method funnels through. Note the argument order: the
     * repository takes {@code (id, userId)}, the reverse of this service's {@code (userId, id)}. Both
     * are UUIDs, so a swap would compile and quietly answer 404 for everything - keeping the only
     * swap on this line is what makes it hard to get wrong.
     */
    private Feeder loadOwned(UUID userId, UUID id) {
        return feederRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new FeederNotFoundException(id));
    }
}
