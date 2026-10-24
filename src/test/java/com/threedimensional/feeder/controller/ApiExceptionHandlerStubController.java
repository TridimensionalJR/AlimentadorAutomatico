package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import com.threedimensional.feeder.exception.InvalidFeederDataException;
import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import com.threedimensional.feeder.exception.UserHasFeedersException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Test fixture, not a production endpoint: routes whose only job is to raise the exceptions the
 * advice maps, so the mapping can be asserted without a real controller and its collaborators.
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
	static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	static final String INVALID_TIMEZONE = "Mars/Olympus";
	/** CR/LF plus the separators {@code \p{Cc}} (C1 NEL) and {@code \p{Zl}}/{@code \p{Zp}} must also cover. */
	static final String FORGED_MESSAGE = "Unknown time-zone ID: Mars/Olympus\r\nERROR\u0085forged\u2028line\u2029entry";
	static final String LONG_REJECTED_VALUE = "A".repeat(300);
	private static final String INVALID_TIMEZONE_PREFIX = "Unknown time-zone ID: ";
	/** Exactly the advice's 200-character limit: the boundary an off-by-one would truncate. */
	static final String AT_LIMIT_MESSAGE = INVALID_TIMEZONE_PREFIX + "B".repeat(200 - INVALID_TIMEZONE_PREFIX.length());

	@GetMapping("/test/feeders/missing")
	void missingFeeder() {
		throw new FeederNotFoundException(FEEDER_ID);
	}

	/** The body is read before the failure, so a handler that echoed the request would leak it. */
	@PostMapping("/test/feeders/missing-with-body")
	void missingFeederWithBody(@RequestBody String body) {
		throw new FeederNotFoundException(FEEDER_ID);
	}

	@GetMapping("/test/devices/conflict")
	void conflictingDevice() {
		throw new DeviceAlreadyRegisteredException(DEVICE_ID);
	}

	@GetMapping("/test/users/missing")
	void missingUser() {
		throw new UserNotFoundException(USER_ID);
	}

	@GetMapping("/test/users/with-feeders")
	void userWithFeeders() {
		throw new UserHasFeedersException(USER_ID);
	}

	/** What the service produces: the domain's IAE, labelled as bad input, message and cause intact. */
	@GetMapping("/test/invalid-content")
	void invalidContent() {
		IllegalArgumentException cause = new IllegalArgumentException("Unknown time-zone ID: " + INVALID_TIMEZONE);
		throw new InvalidFeederDataException(cause.getMessage(), cause);
	}

	/** A rejected value that carries a line break and forged log text, to prove the advice sanitizes it. */
	@GetMapping("/test/invalid-content/injected")
	void injectedContent() {
		throw new InvalidFeederDataException(FORGED_MESSAGE, new IllegalArgumentException(FORGED_MESSAGE));
	}

	/** A rejected value over the log length limit, to prove the advice truncates it. */
	@GetMapping("/test/invalid-content/long")
	void longContent() {
		String message = "Unknown time-zone ID: " + LONG_REJECTED_VALUE;
		throw new InvalidFeederDataException(message, new IllegalArgumentException(message));
	}

	/** A message exactly on the limit, to prove the bound is inclusive and the cut is off-by-one free. */
	@GetMapping("/test/invalid-content/at-limit")
	void contentAtTheLimit() {
		throw new InvalidFeederDataException(AT_LIMIT_MESSAGE, new IllegalArgumentException(AT_LIMIT_MESSAGE));
	}

	/** A domain IAE can arrive without a message; the advice still owes the reader one line. */
	@GetMapping("/test/invalid-content/no-message")
	void contentWithoutMessage() {
		throw new InvalidFeederDataException(null, new IllegalArgumentException());
	}

	/** The verifier logs the reason before throwing; the advice must stay silent. */
	@GetMapping("/test/google-token/rejected")
	void rejectedGoogleToken() {
		throw new InvalidGoogleTokenException("signature does not match");
	}

	/** An IAE from outside the translated domain calls: a bug, and no handler may claim it. */
	@GetMapping("/test/illegal-argument")
	void unmappedIllegalArgument() {
		throw new IllegalArgumentException("Id must not be null");
	}
}
