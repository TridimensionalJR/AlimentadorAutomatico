package com.threedimensional.feeder.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The body of the sign-in call. It carries the ID token Google handed to the front, and only that:
 * email and name are read out of the verified token, never taken from the request, because anything
 * the caller can type is something the caller can forge.
 */
public record GoogleLoginRequest(@NotBlank String idToken) {
}
