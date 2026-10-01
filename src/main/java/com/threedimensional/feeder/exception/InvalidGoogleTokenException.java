package com.threedimensional.feeder.exception;

/**
 * Thrown when the Google ID token handed to sign-in cannot be trusted: malformed, forged,
 * expired, issued for another client, or carrying an email Google has not verified. The
 * controller advice maps it to 401.
 * <p>
 * The message is for the log and is never shown to the caller. Telling an attacker which check
 * failed would only help them probe, so every cause answers with the same generic response.
 */
public class InvalidGoogleTokenException extends RuntimeException {

    public InvalidGoogleTokenException(String message) {
        super(message);
    }

    public InvalidGoogleTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
