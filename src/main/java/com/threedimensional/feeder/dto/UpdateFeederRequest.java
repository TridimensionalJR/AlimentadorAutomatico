package com.threedimensional.feeder.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of a full rename. Both editable labels are replaced, so an absent description clears
 * the stored one - there is no partial update. {@code deviceId}, the owner and {@code active} are
 * absent on purpose: a hardware pairing and an owner never change, and switching the feeder on or
 * off has routes of its own.
 * <p>
 * The size limits mirror the columns, keeping an oversized value from becoming a database error.
 */
public record UpdateFeederRequest(@NotBlank @Size(max = 255) String name,
                                  @Size(max = 255) String description) {
}
