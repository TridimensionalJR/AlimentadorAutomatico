package com.threedimensional.feeder.exception;

import java.util.UUID;

/**
 * Thrown when a device is already paired, whether with the caller's account or another one. Raised
 * by the service pre-check and, when two requests race past it, again when the unique constraint
 * rejects the flush, so both paths end in the same 409 instead of a 500.
 * <p>
 * The device id stays in the message for the log; the response only says what happened.
 */
public class DeviceAlreadyRegisteredException extends RuntimeException {

    public DeviceAlreadyRegisteredException(UUID deviceId) {
        super("device %s is already registered".formatted(deviceId));
    }
}
