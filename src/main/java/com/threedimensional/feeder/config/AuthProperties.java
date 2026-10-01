package com.threedimensional.feeder.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

/**
 * Everything the sign-in flow reads from configuration, bound under the {@code app} prefix.
 * <p>
 * Validated when the context starts, so a missing client id or a weak secret stops the
 * application from booting instead of surfacing on the first login. Registered through
 * {@code @EnableConfigurationProperties} on {@link JwtConfig} rather than a scan on the
 * application class, which keeps the entry point untouched.
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record AuthProperties(@Valid @NotNull Google google, @Valid @NotNull Jwt jwt, @Valid @NotNull Cors cors) {

    /**
     * The OAuth client the front signs in with. The client id is compared with the audience of
     * every incoming ID token: without that check, a token Google issued to some other
     * application would be accepted here.
     */
    public record Google(@NotBlank String clientId) {
    }

    /**
     * The tokens this API issues once Google has vouched for the person. HS256 needs a key of at
     * least 256 bits, and counting characters is a safe floor for that: 32 characters are never
     * fewer than 32 bytes.
     */
    public record Jwt(@NotBlank @Size(min = 32) String secret, @NotBlank String issuer, @NotNull Duration expiration) {
    }

    /** Browser origins allowed to call this API. */
    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }
}
