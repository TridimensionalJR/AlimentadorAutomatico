package com.threedimensional.feeder.dto;

import com.threedimensional.feeder.service.AuthService;

/**
 * The answer to a successful sign-in, in the usual bearer-token shape. {@code expiresIn} is in
 * seconds, so the front can schedule the next sign-in without decoding the token. The profile comes
 * along so the front can show who is signed in without a second round trip.
 */
public record AuthResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {

    private static final String BEARER = "Bearer";

    public static AuthResponse from(AuthService.LoginResult result) {
        return new AuthResponse(
                result.accessToken().value(),
                BEARER,
                result.accessToken().expiresIn().toSeconds(),
                UserResponse.from(result.user()));
    }
}
