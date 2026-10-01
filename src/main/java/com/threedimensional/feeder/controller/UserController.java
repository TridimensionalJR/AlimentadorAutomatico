package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.dto.UserResponse;
import com.threedimensional.feeder.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own account. Protected like everything but sign-in, and the first endpoint
 * that is: it sets the pattern the feeder controllers will follow. The caller is identified by the
 * token and nothing else, through {@link CurrentUser}, and the account is loaded fresh on every call
 * instead of trusting what was true when the token was issued.
 * <p>
 * Doubles as the front's session check: a 200 means the token is valid and the account still
 * exists.
 * <p>
 * There is no id anywhere in these routes, and that is deliberate: the account acted on is always
 * the caller's own, so naming someone else's is not something a request can even express, and no
 * admin role is needed to keep it that way.
 */
@RestController
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        return UserResponse.from(userService.getById(CurrentUser.id(jwt)));
    }

    /**
     * Deletes the caller's own account, answering 204 with no body. Refused with 409 while the
     * account still owns feeders (see {@code UserService#delete}), so removing the devices comes
     * first and nothing is ever destroyed in cascade.
     * <p>
     * The token keeps passing the filter until it expires, since it is not stored anywhere to be
     * revoked, but the account behind it is gone: the next {@code GET /me} answers 404, and signing
     * in again with Google creates a brand new, empty account.
     */
    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal Jwt jwt) {
        userService.delete(CurrentUser.id(jwt));
    }
}
