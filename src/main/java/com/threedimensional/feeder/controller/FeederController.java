package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.dto.CreateFeederRequest;
import com.threedimensional.feeder.dto.FeederResponse;
import com.threedimensional.feeder.dto.UpdateFeederRequest;
import com.threedimensional.feeder.dto.UpdateFeederTimezoneRequest;
import com.threedimensional.feeder.service.FeederService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * The caller's own feeders: registration, listing, relabelling, zone change, on/off and removal.
 * Every route is owner-scoped by construction: the owner comes from the token through
 * {@link CurrentUser} and never from the path, the query or the body, so a request cannot name
 * somebody else's feeder. Another account's feeder answers exactly like one that never existed,
 * because the service filters by owner in the query; this class adds no ownership check of its own.
 * <p>
 * Responses are always {@link FeederResponse}, never the entity: the owner is lazy and is not the
 * client's business, and nothing here reads {@code feeder.getUser()}.
 * <p>
 * Every route is behind {@code anyRequest().authenticated()} in {@code SecurityConfig}, so there is
 * no security annotation to repeat. The body is validated by the DTO annotations before the service
 * is called; what the framework cannot see, such as an unknown zone id, still becomes a 400 through
 * the advice.
 */
@RestController
@RequestMapping("/feeders")
public class FeederController {

    private final FeederService feederService;

    public FeederController(FeederService feederService) {
        this.feederService = feederService;
    }

    /**
     * Pairs a device with the caller's account. The {@code Location} is built from the id the
     * service returned, never from the request, and it is relative on purpose: the id is a UUID, so
     * nothing needs encoding, and the client resolves it against the base it already called.
     */
    @PostMapping
    public ResponseEntity<FeederResponse> create(@AuthenticationPrincipal Jwt jwt,
                                                 @Valid @RequestBody CreateFeederRequest request) {
        FeederResponse response = FeederResponse.from(feederService.create(
                CurrentUser.id(jwt), request.deviceId(), request.name(), request.description(),
                request.timezone()));
        return ResponseEntity.created(URI.create("/feeders/" + response.id())).body(response);
    }

    /**
     * The caller's feeders, with no order guarantee. An account deleted behind a live token gets an
     * empty list: the query filters by owner and needs no account row to answer.
     */
    @GetMapping
    public List<FeederResponse> list(@AuthenticationPrincipal Jwt jwt) {
        return feederService.list(CurrentUser.id(jwt)).stream().map(FeederResponse::from).toList();
    }

    @GetMapping("/{id}")
    public FeederResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return FeederResponse.from(feederService.get(CurrentUser.id(jwt), id));
    }

    /**
     * Replaces both labels, following {@code Feeder.update}: an omitted description clears the
     * stored one. {@code active} and the device pairing have routes of their own and are absent
     * here on purpose.
     */
    @PutMapping("/{id}")
    public FeederResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                 @Valid @RequestBody UpdateFeederRequest request) {
        return FeederResponse.from(
                feederService.update(CurrentUser.id(jwt), id, request.name(), request.description()));
    }

    @PutMapping("/{id}/timezone")
    public FeederResponse updateTimezone(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
                                         @Valid @RequestBody UpdateFeederTimezoneRequest request) {
        return FeederResponse.from(
                feederService.updateTimezone(CurrentUser.id(jwt), id, request.timezone()));
    }

    /** Idempotent: the current state is the answer, not a transition that has to happen. */
    @PostMapping("/{id}/activate")
    public FeederResponse activate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return FeederResponse.from(feederService.activate(CurrentUser.id(jwt), id));
    }

    /** Idempotent, mirroring {@link #activate}. */
    @PostMapping("/{id}/deactivate")
    public FeederResponse deactivate(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        return FeederResponse.from(feederService.deactivate(CurrentUser.id(jwt), id));
    }

    /**
     * Removes one feeder and answers 204 with no body. Its schedules go with it through the FK's
     * {@code ON DELETE CASCADE}; this class never sees them.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        feederService.delete(CurrentUser.id(jwt), id);
    }
}
