package com.threedimensional.feeder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * The body of a registration. It carries only what the caller may decide - the hardware
 * {@code deviceId} and the labels - because the owner is taken from the token, never from the
 * body: a field that could name an owner would let one account register a device for another.
 * <p>
 * The size limits mirror the columns ({@code name}/{@code description} 255, {@code timezone} 64),
 * so an oversized value is rejected as the 400 it is instead of reaching the database and
 * surfacing as a 500. Only {@code name} is checked for blankness. A null {@code timezone} means
 * "keep the domain default"; an empty or unknown zone is rejected by the domain, not here.
 */
public record CreateFeederRequest(@NotNull UUID deviceId,
                                  @NotBlank @Size(max = 255) String name,
                                  @Size(max = 255) String description,
                                  @Size(max = 64) String timezone) {
}
