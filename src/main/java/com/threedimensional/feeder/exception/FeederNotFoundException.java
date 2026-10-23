package com.threedimensional.feeder.exception;

import java.util.UUID;

/**
 * Thrown when a feeder cannot be found for the caller, either because the id never existed or
 * because the feeder belongs to another account. Both cases share this exception on purpose: the
 * API answers one identical 404, so the response cannot be used to learn that somebody else's
 * feeder exists.
 * <p>
 * The id travels in the message for the log only; the controller advice never returns it.
 */
public class FeederNotFoundException extends RuntimeException {

    public FeederNotFoundException(UUID id) {
        super("feeder %s does not exist".formatted(id));
    }
}
