package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.dto.AuthResponse;
import com.threedimensional.feeder.dto.GoogleLoginRequest;
import com.threedimensional.feeder.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in. Public by design, the only endpoint that is, since the caller has no token of ours yet.
 * Always answers 200 on success, for new and returning users alike. There is nothing to branch on
 * here: the work, and the decision of whether an account has to be created, is in the service.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/google")
    public AuthResponse loginWithGoogle(@Valid @RequestBody GoogleLoginRequest request) {
        return AuthResponse.from(authService.loginWithGoogle(request.idToken()));
    }
}
