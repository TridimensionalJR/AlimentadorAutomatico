package com.threedimensional.feeder.exception;

/**
 * Labels an {@link IllegalArgumentException} that came from a domain call validating client input,
 * so the API can answer 400 for exactly those rejections and nothing else.
 * <p>
 * The domain keeps throwing {@code IllegalArgumentException}: that is its language and its tests
 * depend on it. This exception is the label the service puts on a rejection only when it knows the
 * value came from the request. Without it, a 400 handler would have to trust every IAE in the
 * request path, and an infrastructure bug ({@code Id must not be null}) would be reported as the
 * caller's fault instead of a 500.
 * <p>
 * The message is the domain's own and names the rejected value, so it exists for the log only; the
 * advice never returns it. The cause is typed {@code IllegalArgumentException} on purpose: there is
 * no translation for any other failure, such as a {@code DataIntegrityViolationException}.
 */
public class InvalidFeederDataException extends RuntimeException {

    public InvalidFeederDataException(String message, IllegalArgumentException cause) {
        super(message, cause);
    }
}
