package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.config.AuthProperties;
import com.threedimensional.feeder.config.JwtConfig;
import com.threedimensional.feeder.config.SecurityConfig;
import com.threedimensional.feeder.exception.InvalidGoogleTokenException;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.service.AuthService;
import com.threedimensional.feeder.service.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of sign-in and the security rules around it, through the real filter chain:
 * what is public, what is not, which tokens are accepted, and which browser origins may call.
 * The service is mocked, since its own behavior is covered in {@code AuthServiceTests}.
 * <p>
 * A {@code @WebMvcTest} slice does not pick up {@code @Configuration} classes or {@code @Service}
 * beans, hence the {@code @Import}. The token-related tests use the real {@link JwtService}, so a
 * token the filter accepts here is one the application would genuinely issue.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtConfig.class, JwtService.class})
class AuthControllerTests {

	private static final UUID USER_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private AuthProperties properties;

	@MockitoBean
	private AuthService authService;

	/**
	 * The application class carries {@code @EnableJpaAuditing}, which a web slice picks up without
	 * any JPA infrastructure behind it and fails with "JPA metamodel must not be empty". Replacing
	 * the mapping context lets the slice start without touching the entry point.
	 */
	@MockitoBean
	private JpaMetamodelMappingContext jpaMetamodelMappingContext;

	// --- Sign-in ------------------------------------------------------------------------------

	@Test
	void loginWithGoogle_shouldReturnTokenAndProfile_whenCredentialIsValid() throws Exception {
		when(authService.loginWithGoogle("google-id-token")).thenReturn(
				new AuthService.LoginResult(user(), new JwtService.AccessToken("jwt-value", Duration.ofHours(1))));

		mockMvc.perform(post("/auth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"google-id-token\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accessToken").value("jwt-value"))
				.andExpect(jsonPath("$.tokenType").value("Bearer"))
				.andExpect(jsonPath("$.expiresIn").value(3600))
				.andExpect(jsonPath("$.user.id").value(USER_ID.toString()))
				.andExpect(jsonPath("$.user.email").value("tiago@example.com"))
				.andExpect(jsonPath("$.user.name").value("Tiago"));
	}

	/**
	 * The response must never expose the Google subject. Asserting its absence pins that the
	 * entity is mapped to a DTO instead of being serialized as it is.
	 */
	@Test
	void loginWithGoogle_shouldNotExposeTheGoogleSubject() throws Exception {
		when(authService.loginWithGoogle("google-id-token")).thenReturn(
				new AuthService.LoginResult(user(), new JwtService.AccessToken("jwt-value", Duration.ofHours(1))));

		mockMvc.perform(post("/auth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"google-id-token\"}"))
				.andExpect(jsonPath("$.user.googleId").doesNotExist());
	}

	/** Nothing reaches the service when the body has no usable token. */
	@ParameterizedTest
	@ValueSource(strings = {"{}", "{\"idToken\":\"\"}", "{\"idToken\":\"   \"}", "not json"})
	void loginWithGoogle_shouldReturn400_whenBodyHasNoToken(String body) throws Exception {
		mockMvc.perform(post("/auth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(authService);
	}

	/** The body says nothing about why the credential failed, only that it did. */
	@Test
	void loginWithGoogle_shouldReturn401_whenCredentialIsRejected() throws Exception {
		when(authService.loginWithGoogle("forged"))
				.thenThrow(new InvalidGoogleTokenException("signature does not match"));

		mockMvc.perform(post("/auth/google")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"idToken\":\"forged\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.detail").value("invalid Google credential"));
	}

	// --- Everything else is protected ---------------------------------------------------------

	/**
	 * No route exists at this path yet, which is the point: protection is the default for whatever
	 * is added next, so the rejection comes before any routing question is asked.
	 */
	@Test
	void anyOtherRoute_shouldReturn401_whenThereIsNoToken() throws Exception {
		mockMvc.perform(get("/feeders"))
				.andExpect(status().isUnauthorized());
	}

	/** The 404 proves the request got past security: the token was accepted, the route is simply absent. */
	@Test
	void anyOtherRoute_shouldGetPastSecurity_whenTheTokenIsOneWeIssued() throws Exception {
		String token = jwtService.issue(user()).value();

		mockMvc.perform(get("/feeders").header("Authorization", "Bearer " + token))
				.andExpect(status().isNotFound());
	}

	@Test
	void anyOtherRoute_shouldReturn401_whenTheTokenIsExpired() throws Exception {
		Clock longAgo = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
		String expired = new JwtService(jwtEncoder, properties, longAgo).issue(user()).value();

		mockMvc.perform(get("/feeders").header("Authorization", "Bearer " + expired))
				.andExpect(status().isUnauthorized());
	}

	/** A token signed with a key that is not ours must never authenticate, however well formed. */
	@Test
	void anyOtherRoute_shouldReturn401_whenTheTokenWasSignedWithAnotherKey() throws Exception {
		AuthProperties foreign = new AuthProperties(
				properties.google(),
				new AuthProperties.Jwt("a-completely-different-secret-of-32-plus-chars", properties.jwt().issuer(),
						Duration.ofHours(1)),
				properties.cors());
		JwtService foreignService = new JwtService(new JwtConfig().jwtEncoder(foreign), foreign, Clock.systemUTC());

		mockMvc.perform(get("/feeders").header("Authorization", "Bearer " + foreignService.issue(user()).value()))
				.andExpect(status().isUnauthorized());
	}

	// --- Browser access -----------------------------------------------------------------------

	/**
	 * The browser sends this preflight before the real call, with no token. It has to be answered
	 * ahead of authentication, otherwise the front could never sign in from its own origin.
	 */
	@Test
	void preflight_shouldBeAllowed_forTheConfiguredOrigin() throws Exception {
		mockMvc.perform(options("/auth/google")
						.header("Origin", ALLOWED_ORIGIN)
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isOk())
				.andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));
	}

	@Test
	void preflight_shouldBeRefused_forAnyOtherOrigin() throws Exception {
		mockMvc.perform(options("/auth/google")
						.header("Origin", "https://evil.example.com")
						.header("Access-Control-Request-Method", "POST"))
				.andExpect(status().isForbidden());
	}

	// --- Helpers ------------------------------------------------------------------------------

	/** User has no setters and no id until persisted, so the id is placed by hand. */
	private static User user() {
		User user = User.create("google-abc", "tiago@example.com", "Tiago");
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}
}
