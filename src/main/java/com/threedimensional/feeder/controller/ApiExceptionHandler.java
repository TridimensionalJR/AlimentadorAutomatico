package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import com.threedimensional.feeder.exception.InvalidFeederDataException;
import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import com.threedimensional.feeder.exception.UserHasFeedersException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns exceptions into RFC 9457 problem responses. Extending {@link ResponseEntityExceptionHandler}
 * gives the framework's own failures, a body that fails validation or is not valid JSON, the same
 * shape and a 400 for free, so only this application's exceptions are mapped by hand.
 * <p>
 * Only what a controller can raise today is mapped, which is everything this application throws on
 * purpose so far.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Deliberately generic: the exception knows exactly which check failed, and the caller is told
     * none of it. The detail is in the log.
     */
    @ExceptionHandler(InvalidGoogleTokenException.class)
    public ProblemDetail handleInvalidGoogleToken(InvalidGoogleTokenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid Google credential");
    }

    /**
     * The 404 its javadoc promised. Today the only way to meet it is a valid token whose account has
     * been deleted since it was issued. The exception message carries the id, so it is left out of
     * the response, which only says what is missing.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "user not found");
    }

    /**
     * The 409 its javadoc promised. Unlike the other two, this one is told to the caller on purpose:
     * it is the only way to learn what to do next, and it reveals nothing the caller does not
     * already own. The id in the exception message is still left out.
     */
    @ExceptionHandler(UserHasFeedersException.class)
    public ProblemDetail handleUserHasFeeders(UserHasFeedersException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the account still has feeders: remove them before deleting it");
    }

    /**
     * A feeder of another account answers exactly like one that never existed: same status, same
     * fixed detail. The id carried by the exception message is for the log only.
     */
    @ExceptionHandler(FeederNotFoundException.class)
    public ProblemDetail handleFeederNotFound(FeederNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "feeder not found");
    }

    /**
     * One 409 for both the pre-check and the unique constraint losing the race: the caller's next
     * step is the same, and the device id it sent is not echoed back.
     */
    @ExceptionHandler(DeviceAlreadyRegisteredException.class)
    public ProblemDetail handleDeviceAlreadyRegistered(DeviceAlreadyRegisteredException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "device already registered");
    }

    /**
     * Bad input the domain rejected: an unknown zone, or a blank name that got past the DTO. 400 is
     * the honest translation, and the message naming the rejected value stays in the exception (and
     * in its cause) instead of the response. Every other {@link IllegalArgumentException} is a bug
     * and is deliberately left unmapped, so it surfaces as a 500 rather than being blamed on the
     * caller.
     */
    @ExceptionHandler(InvalidFeederDataException.class)
    public ProblemDetail handleInvalidFeederData(InvalidFeederDataException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid request content");
    }
}
