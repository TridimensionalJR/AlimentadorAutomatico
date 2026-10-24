package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import com.threedimensional.feeder.exception.InvalidFeederDataException;
import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import com.threedimensional.feeder.exception.UserHasFeedersException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.regex.Pattern;

/**
 * Turns exceptions into RFC 9457 problem responses. Extending {@link ResponseEntityExceptionHandler}
 * gives the framework's own failures, a body that fails validation or is not valid JSON, the same
 * shape and a 400 for free, so only this application's exceptions are mapped by hand.
 * <p>
 * Only what a controller can raise today is mapped, which is everything this application throws on
 * purpose so far.
 * <p>
 * Every handler for this application's exceptions writes exactly one {@code INFO} line carrying the
 * exception message and nothing else; only the message varies, and it is sanitized first. The
 * caller still gets the fixed detail. {@link InvalidGoogleTokenException} is the one exception: its
 * reason was already logged by {@code GoogleTokenVerifier}, so logging it here would report the
 * same rejection twice.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /**
     * Control characters (Unicode category {@code Cc}: C0 and C1, so CR, LF and U+0085 NEL included)
     * and the Unicode line/paragraph separators: the characters that can start a new line, here or in
     * a downstream consumer. Not {@code \p{Cntrl}}: in Java that is the POSIX US-ASCII range
     * {@code [\x00-\x1F\x7F]}, which silently lets C1 through.
     */
    private static final Pattern LOG_BREAKERS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}]");

    /** Keeps one hostile message from becoming a megabyte of log. */
    private static final int MAX_LOG_LENGTH = 200;

    /**
     * One line, bounded, and never interpreted as a format string: the message comes from the client.
     * {@code \p{Cc}} is Unicode category <i>Other, control</i>, so it covers C0 and C1, including
     * {@code \r\n} and U+0085 NEL; {@code \p{Zl}}/{@code \p{Zp}} cover U+2028/U+2029, which are not
     * control characters and still break some sinks. Format characters (bidi overrides) are left
     * alone: they cannot start a new line, only reorder one.
     */
    private static String forLog(String message) {
        if (message == null) {
            return "<no message>";
        }
        String singleLine = LOG_BREAKERS.matcher(message).replaceAll("_");
        return singleLine.length() <= MAX_LOG_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_LOG_LENGTH) + "...";
    }

    /**
     * Deliberately generic: the exception knows exactly which check failed, and the caller is told
     * none of it. The reason was logged by {@code GoogleTokenVerifier} when it rejected the token,
     * so this handler writes nothing and a rejection appears in the log exactly once.
     */
    @ExceptionHandler(InvalidGoogleTokenException.class)
    public ProblemDetail handleInvalidGoogleToken(InvalidGoogleTokenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "invalid Google credential");
    }

    /**
     * The 404 its javadoc promised. Today the only way to meet it is a valid token whose account has
     * been deleted since it was issued. The exception message carries the id, which this handler
     * logs; the response only says what is missing.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ProblemDetail handleUserNotFound(UserNotFoundException e) {
        log.info("User not found: {}", forLog(e.getMessage()));
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "user not found");
    }

    /**
     * The 409 its javadoc promised. Unlike the other two, this one is told to the caller on purpose:
     * it is the only way to learn what to do next, and it reveals nothing the caller does not
     * already own. The id in the exception message is logged and left out of the response.
     */
    @ExceptionHandler(UserHasFeedersException.class)
    public ProblemDetail handleUserHasFeeders(UserHasFeedersException e) {
        log.info("User still owns feeders: {}", forLog(e.getMessage()));
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
                "the account still has feeders: remove them before deleting it");
    }

    /**
     * A feeder of another account answers exactly like one that never existed: same status, same
     * fixed detail. The id carried by the exception message is logged by this handler and never
     * returned.
     */
    @ExceptionHandler(FeederNotFoundException.class)
    public ProblemDetail handleFeederNotFound(FeederNotFoundException e) {
        log.info("Feeder not found: {}", forLog(e.getMessage()));
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "feeder not found");
    }

    /**
     * One 409 for both the pre-check and the unique constraint losing the race: the caller's next
     * step is the same, and the device id it sent is logged, not echoed back.
     */
    @ExceptionHandler(DeviceAlreadyRegisteredException.class)
    public ProblemDetail handleDeviceAlreadyRegistered(DeviceAlreadyRegisteredException e) {
        log.info("Device already registered: {}", forLog(e.getMessage()));
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "device already registered");
    }

    /**
     * Bad input the domain rejected: an unknown zone, or a blank name that got past the DTO. 400 is
     * the honest translation, and the message naming the rejected value is logged sanitized and
     * kept out of the response. Every other {@link IllegalArgumentException} is a bug and is
     * deliberately left unmapped, so it surfaces as a 500 rather than being blamed on the caller.
     */
    @ExceptionHandler(InvalidFeederDataException.class)
    public ProblemDetail handleInvalidFeederData(InvalidFeederDataException e) {
        log.info("Invalid feeder data: {}", forLog(e.getMessage()));
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "invalid request content");
    }
}
