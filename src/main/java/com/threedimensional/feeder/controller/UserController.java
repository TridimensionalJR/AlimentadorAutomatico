package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.dto.UserResponse;
import com.threedimensional.feeder.service.UserService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in user's own account. Protected like everything but sign-in, and the first endpoint
 * that is: it sets the pattern the feeder controllers will follow. The caller is identified by the
 * token and nothing else, through {@link CurrentUser}, and the account is loaded fresh on every call
 * instead of trusting what was true when the token was issued.
 * <p>
 * Doubles as the front's session check: a 200 means the token is valid and the account still
 * exists.
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
}
