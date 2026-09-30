package com.threedimensional.feeder.exception;

import java.util.UUID;

/**
 * Thrown by account deletion while the user still owns feeders. The paired devices are exactly
 * why the schema restricts feeders - users instead of cascading; this exception turns that
 * database rule into a clear, 409-mappable signal so the controller never surfaces a raw
 * constraint violation.
 */
public class UserHasFeedersException extends RuntimeException {

    public UserHasFeedersException(UUID id) {
        super("user %s still owns feeders and cannot be deleted".formatted(id));
    }
}