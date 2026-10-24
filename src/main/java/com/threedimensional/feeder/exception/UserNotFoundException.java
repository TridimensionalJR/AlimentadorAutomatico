package com.threedimensional.feeder.exception;

import java.util.UUID;

/**
 * Thrown when no account matches the given id. Deletion of an unknown id lands here;
 * controllers map it to 404 when they arrive.
 * <p>
 * The id travels in the message, which the advice logs; the response never carries it.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(UUID id) {
        super("user %s does not exist".formatted(id));
    }
}