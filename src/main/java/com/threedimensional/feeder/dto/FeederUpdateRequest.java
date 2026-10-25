
package com.threedimensional.feeder.dto;

import jakarta.validation.constraints.NotBlank;

// Defines the fields that can be updated after registration.
public record FeederUpdateRequest(
        @NotBlank String name,
        String description,
        String timezone
) {
}