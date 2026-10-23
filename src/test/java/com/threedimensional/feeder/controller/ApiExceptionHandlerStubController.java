package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Test fixture, not a production endpoint: three routes whose only job is to raise the exceptions
 * the advice maps, so the mapping can be asserted without a real controller and its collaborators.
 * <p>
 * Top-level and package-private on purpose. {@code @WebMvcTest(controllers = ...)} only filters
 * component scanning, it does not register anything; a class in the scanned package (test classes
 * are on the classpath too) is found exactly like a production controller, whereas a nested stub
 * would need {@code @Import} and could end up registered twice.
 */
@RestController
class ApiExceptionHandlerStubController {

    static final UUID FEEDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    static final String INVALID_TIMEZONE = "Mars/Olympus";

    @GetMapping("/test/feeders/missing")
    void missingFeeder() {
        throw new FeederNotFoundException(FEEDER_ID);
    }

    @GetMapping("/test/devices/conflict")
    void conflictingDevice() {
        throw new DeviceAlreadyRegisteredException(DEVICE_ID);
    }

    @GetMapping("/test/invalid-content")
    void invalidContent() {
        throw new IllegalArgumentException("Unknown time-zone ID: " + INVALID_TIMEZONE);
    }
}
