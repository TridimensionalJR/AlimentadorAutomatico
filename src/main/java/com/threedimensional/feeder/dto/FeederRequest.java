
package com.threedimensional.feeder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// Data received in the request to register a feeder.
public record FeederRequest(
        @NotNull UUID deviceId,
        @NotBlank String name,
        String description,
        String timezone
) {
}