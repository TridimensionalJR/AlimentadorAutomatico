package com.threedimensional.feeder.service;

/**
 * What sign-in takes from a verified Google ID token: exactly the three arguments of
 * {@link UserService#getOrCreateByGoogle}. {@code name} is null when the Google account supplies
 * none, since the OIDC {@code name} claim is optional, and the user service owns the fallback.
 */
public record GoogleIdentity(String googleId, String email, String name) {
}
