package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
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
 * Only what a controller can raise today is mapped. {@code UserHasFeedersException} waits for the
 * controller that will throw it, as its own javadoc already anticipates.
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
}
