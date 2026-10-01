package com.threedimensional.feeder.controller;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * The one place a controller learns who is calling. {@code JwtService} writes the internal
 * {@code User.id} into the token subject, and this reads it back, so that knowledge lives in two
 * mirrored spots and nowhere else.
 * <p>
 * Every endpoint that acts on the caller's own data takes its owner from here. It is never read from
 * the path, the query or the body: anything the caller can type is something the caller can forge,
 * and the token is the only identity this API has verified.
 * <p>
 * The subject is always a UUID because only tokens this API signed get this far, and it only ever
 * signs a user id.
 */
final class CurrentUser {

    private CurrentUser() {
    }

    static UUID id(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
