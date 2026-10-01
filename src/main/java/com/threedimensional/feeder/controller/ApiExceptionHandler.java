package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
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
 * Only what a controller can raise today is mapped. {@code UserNotFoundException} and
 * {@code UserHasFeedersException} wait for the controllers that will throw them, as their own
 * javadoc already anticipates.
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
}
