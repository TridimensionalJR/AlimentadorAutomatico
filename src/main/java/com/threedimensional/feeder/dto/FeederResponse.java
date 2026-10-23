package com.threedimensional.feeder.dto;

import com.threedimensional.feeder.model.Feeder;

import java.time.Instant;
import java.util.UUID;

/**
 * A feeder as the front sees it. The entity is never serialized directly: it carries the owning
 * association, which is lazy and cannot be read once the transaction is over
 * ({@code open-in-view=false}), and the owner is not the client's business - the caller already
 * knows who they are. There is deliberately no owner field to leak.
 * <p>
 * {@code active} is derived with a null-safe comparison, so a half-built entity maps to
 * {@code false} instead of throwing. Nulls are kept and serialized as null on purpose: an absent
 * {@code description} and an empty one mean different things, and an entity not yet flushed has
 * no {@code updatedAt} yet.
 */
public record FeederResponse(UUID id, UUID deviceId, String name, String description, String timezone,
                             boolean active, Instant createdAt, Instant updatedAt) {

    public static FeederResponse from(Feeder feeder) {
        return new FeederResponse(
                feeder.getId(),
                feeder.getDeviceId(),
                feeder.getName(),
                feeder.getDescription(),
                feeder.getTimezone(),
                Boolean.TRUE.equals(feeder.getIsActive()),
                feeder.getCreatedAt(),
                feeder.getUpdatedAt());
    }
}
