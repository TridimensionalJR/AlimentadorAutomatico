package com.threedimensional.feeder.dto;

import com.threedimensional.feeder.model.User;

import java.util.UUID;

/**
 * The account as the front sees it. The entity is never serialized directly: it also holds the
 * Google subject, which has no business leaving the backend.
 */
public record UserResponse(UUID id, String email, String name) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName());
    }
}
