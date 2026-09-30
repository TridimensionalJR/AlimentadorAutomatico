package com.threedimensional.feeder.exception;

import java.util.UUID;

/**
 * Thrown when no account matches the given id. Deletion of an unknown id lands here;
 * controllers map it to 404 when they arrive.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID id) {
        super("user %s does not exist".formatted(id));
    }
}