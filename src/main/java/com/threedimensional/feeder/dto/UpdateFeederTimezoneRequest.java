package com.threedimensional.feeder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of a zone change, which has a route of its own because a feeder's zone is one value
 * shared by every rule it owns. Whether the value names a real IANA zone is not checked here:
 * {@code Feeder.updateTimezone} owns that rule, {@code FeederService} translates its rejection into
 * {@code InvalidFeederDataException} and the advice maps that to a 400.
 * <p>
 * The size limit mirrors the column, so what cannot be stored cannot be sent.
 */
public record UpdateFeederTimezoneRequest(@NotBlank @Size(max = 64) String timezone) {
}
