
package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.dto.FeederRequest;
import com.threedimensional.feeder.dto.FeederUpdateRequest;
import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.service.FeederService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/feeders")
public class FeederController {

    private final FeederService feederService;

    public FeederController(FeederService feederService) {
        this.feederService = feederService;
    }

    // CREATE: POST /api/feeders
    @PostMapping
    public ResponseEntity<Feeder> create(
            @Valid @RequestBody FeederRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        // Retrieves the user ID from the JWT token.
        Feeder createdFeeder = feederService.create(
                CurrentUser.id(jwt),
                request
        );

        // Returns HTTP 201 Created and the URI of the created resource.
        return ResponseEntity
                .created(URI.create(
                        "/api/feeders/" + createdFeeder.getId()
                ))
                .body(createdFeeder);
    }

    // READ: GET /api/feeders/{id}
    @GetMapping("/{id}")
    public ResponseEntity<Feeder> findById(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        // Returns the feeder if it belongs to the authenticated user.
        return feederService.findById(id, CurrentUser.id(jwt))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // READ: GET /api/feeders?page=0
    @GetMapping
    public ResponseEntity<?> findAll(
            @RequestParam(defaultValue = "0") int page,
            @AuthenticationPrincipal Jwt jwt) {

        // The first page is 0; the service limits each page to 50 items.
        return ResponseEntity.ok(
                feederService.findByPage(
                        CurrentUser.id(jwt),
                        Math.max(page, 0)
                )
        );
    }

    // UPDATE: PUT /api/feeders/{id}
    @PutMapping("/{id}")
    public ResponseEntity<Feeder> update(
            @PathVariable UUID id,
            @Valid @RequestBody FeederUpdateRequest request,
            @AuthenticationPrincipal Jwt jwt) {

        // Updates the feeder only if it belongs to the authenticated user.
        return feederService.update(
                        id,
                        CurrentUser.id(jwt),
                        request
                )
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // DELETE: DELETE /api/feeders/{id}
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {

        // Returns 404 if the feeder does not exist or does not belong
        // to the authenticated user.
        if (!feederService.delete(id, CurrentUser.id(jwt))) {
            return ResponseEntity.notFound().build();
        }

        // Returns HTTP 204 No Content when the deletion succeeds.
        return ResponseEntity.noContent().build();
    }
}
