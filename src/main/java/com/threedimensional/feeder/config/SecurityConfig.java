package com.threedimensional.feeder.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

/**
 * Who may call what. Everything requires a valid application token except the sign-in exchange,
 * which is the one place a caller has no token yet.
 * <p>
 * Relies on the beans of {@link JwtConfig}, which is also what registers {@link AuthProperties}.
 */
@Configuration
public class SecurityConfig {

    /**
     * Stateless: the token is the whole session, so nothing is kept server side and there is no
     * cookie for a cross-site request to ride on. That is also why CSRF protection is switched off,
     * it defends cookie-based sessions and this API has none.
     * <p>
     * {@code /error} is open because the container forwards failures there, and a protected
     * {@code /error} would turn every genuine 4xx/5xx into an unexplained 401.
     * <p>
     * The decoder is named explicitly instead of left to be found by type, so which tokens guard
     * the API is visible right here and cannot change by adding another decoder bean.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      @Qualifier("appJwtDecoder") JwtDecoder appJwtDecoder) {
        http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/google")).permitAll()
                        .requestMatchers(PathPatternRequestMatcher.pathPattern("/error")).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt -> jwt.decoder(appJwtDecoder)));
        return http.build();
    }

    /**
     * Keeps the H2 console usable in development, where it would otherwise be blocked by the chain
     * above. It sits in its own chain, matched first, so the exception stays confined to that path
     * and to the dev profile. Frames are allowed from the same origin because the console renders
     * itself inside a frame.
     */
    @Bean
    @Order(1)
    @Profile("dev")
    public SecurityFilterChain h2ConsoleSecurityFilterChain(HttpSecurity http) {
        http
                .securityMatcher(PathPatternRequestMatcher.pathPattern("/h2-console/**"))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable())
                .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
        return http.build();
    }

    /**
     * Origins come from configuration because the front lives elsewhere in every environment.
     * Credentials stay off: the token travels in the Authorization header, never in a cookie, so
     * there is nothing for the browser to attach on its own.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AuthProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.cors().allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
