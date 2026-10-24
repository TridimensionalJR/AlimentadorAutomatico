package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.config.JwtConfig;
import com.threedimensional.feeder.config.SecurityConfig;
import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import com.threedimensional.feeder.exception.InvalidFeederDataException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.service.FeederService;
import com.threedimensional.feeder.service.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@link FeederController}: every route, its status and body, and — as
 * important — which account the service is told to act on. Tokens come from the real
 * {@link JwtService}, through the real filter chain, so what is exercised is the whole path a
 * client takes; the service is mocked because its behavior is covered in FeederServiceTests.
 * <p>
 * Same slice setup as UserControllerTests: security and token beans are imported explicitly
 * because a web slice scans neither {@code @Configuration} nor {@code @Service} classes.
 */
@WebMvcTest(FeederController.class)
@Import({SecurityConfig.class, JwtConfig.class, JwtService.class})
class FeederControllerTests {

	private static final UUID USER_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
	private static final UUID OTHER_USER_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
	private static final UUID FEEDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID OTHER_DEVICE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-01-02T04:05:06Z");
	private static final String CREATE_BODY =
			"{\"deviceId\":\"" + DEVICE_ID + "\",\"name\":\"Breakfast feeder\",\"description\":\"Kitchen\"}";
	private static final String UPDATE_BODY = "{\"name\":\"Evening feeder\",\"description\":\"Porch\"}";
	private static final String TIMEZONE_BODY = "{\"timezone\":\"America/Manaus\"}";
	private static final String INVALID_TIMEZONE = "Mars/Olympus";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private FeederService feederService;

	/**
	 * Same reason as in UserControllerTests: the application class enables JPA auditing, which a
	 * web slice picks up without the JPA infrastructure behind it.
	 */
	@MockitoBean
	private JpaMetamodelMappingContext jpaMetamodelMappingContext;

	// --- Registering --------------------------------------------------------------------------

	/**
	 * The whole registration contract in one shot: 201, a relative {@code Location} built from the
	 * id the service returned, the mapped body and no trace of the owner. The exact
	 * {@code verify} proves the labels and the absent timezone reach the service untouched, and that
	 * the owner is the token subject.
	 */
	@Test
	void create_shouldReturn201_withTheCreatedFeederAndARelativeLocation() throws Exception {
		when(feederService.create(USER_ID, DEVICE_ID, "Breakfast feeder", "Kitchen", null)).thenReturn(feeder());

		mockMvc.perform(post("/feeders").header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/feeders/" + FEEDER_ID))
				.andExpect(jsonPath("$.id").value(FEEDER_ID.toString()))
				.andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
				.andExpect(jsonPath("$.name").value("Breakfast feeder"))
				.andExpect(jsonPath("$.description").value("Kitchen"))
				.andExpect(jsonPath("$.timezone").value("America/Bahia"))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.createdAt").value(CREATED_AT.toString()))
				.andExpect(jsonPath("$.updatedAt").value(UPDATED_AT.toString()))
				.andExpect(jsonPath("$.user").doesNotExist())
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andExpect(jsonPath("$.googleId").doesNotExist());

		verify(feederService).create(USER_ID, DEVICE_ID, "Breakfast feeder", "Kitchen", null);
		verifyNoMoreInteractions(feederService);
	}

	/** A zone named in the body is forwarded as written; the controller neither trims nor defaults it. */
	@Test
	void create_shouldForwardTheTimezone_whenTheBodyNamesOne() throws Exception {
		when(feederService.create(USER_ID, DEVICE_ID, "Breakfast feeder", null, "America/Manaus"))
				.thenReturn(feederInManaus());

		mockMvc.perform(post("/feeders").header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"deviceId\":\"" + DEVICE_ID
								+ "\",\"name\":\"Breakfast feeder\",\"timezone\":\"America/Manaus\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.timezone").value("America/Manaus"));

		verify(feederService).create(USER_ID, DEVICE_ID, "Breakfast feeder", null, "America/Manaus");
		verifyNoMoreInteractions(feederService);
	}

	/**
	 * Anti mass-assignment, proved at the HTTP boundary: an attacker-controlled {@code id},
	 * {@code userId}, {@code active}, {@code isActive} and nested {@code user} in the body reach
	 * nothing, because the DTO has no component for them. The owner, the state and the
	 * {@code Location} the service receives are the token's and the domain's.
	 */
	@Test
	void create_shouldIgnoreOwnerAndActiveFields_whenTheBodyCarriesThem() throws Exception {
		when(feederService.create(USER_ID, DEVICE_ID, "Breakfast feeder", null, null)).thenReturn(feeder());

		mockMvc.perform(post("/feeders").header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"id\":\"" + OTHER_USER_ID + "\",\"deviceId\":\"" + DEVICE_ID
								+ "\",\"name\":\"Breakfast feeder\",\"userId\":\"" + OTHER_USER_ID
								+ "\",\"isActive\":false,\"active\":false,\"user\":{\"id\":\"" + OTHER_USER_ID + "\"}}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/feeders/" + FEEDER_ID))
				.andExpect(jsonPath("$.id").value(FEEDER_ID.toString()))
				.andExpect(jsonPath("$.active").value(true));

		verify(feederService).create(USER_ID, DEVICE_ID, "Breakfast feeder", null, null);
		verifyNoMoreInteractions(feederService);
	}

	/** One 409 for a device already paired, whoever owns it, and the device id is not echoed back. */
	@Test
	void create_shouldReturn409_whenTheDeviceIsAlreadyRegistered() throws Exception {
		when(feederService.create(USER_ID, DEVICE_ID, "Breakfast feeder", "Kitchen", null))
				.thenThrow(new DeviceAlreadyRegisteredException(DEVICE_ID));

		mockMvc.perform(post("/feeders").header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("device already registered"))
				.andExpect(content().string(not(containsString(DEVICE_ID.toString()))));

		verify(feederService).create(USER_ID, DEVICE_ID, "Breakfast feeder", "Kitchen", null);
	}

	/** A token can outlive its account, and registration is the one route where the account is required. */
	@Test
	void create_shouldReturn404_whenTheTokenOwnerNoLongerExists() throws Exception {
		when(feederService.create(USER_ID, DEVICE_ID, "Breakfast feeder", "Kitchen", null))
				.thenThrow(new UserNotFoundException(USER_ID));

		mockMvc.perform(post("/feeders").header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("user not found"));

		verify(feederService).create(USER_ID, DEVICE_ID, "Breakfast feeder", "Kitchen", null);
	}

	/**
	 * A zone the domain rejects is a client error, answered with the fixed detail: the message
	 * naming the rejected value stays out of the body, since it would help map what is accepted.
	 */
	@Test
	void create_shouldReturn400_whenTheDomainRejectsTheTimezone() throws Exception {
		when(feederService.create(USER_ID, DEVICE_ID, "Breakfast feeder", null, INVALID_TIMEZONE))
				.thenThrow(invalidTimezone());

		mockMvc.perform(post("/feeders").header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"deviceId\":\"" + DEVICE_ID
								+ "\",\"name\":\"Breakfast feeder\",\"timezone\":\"" + INVALID_TIMEZONE + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("invalid request content"))
				.andExpect(content().string(not(containsString(INVALID_TIMEZONE))));

		verify(feederService).create(USER_ID, DEVICE_ID, "Breakfast feeder", null, INVALID_TIMEZONE);
	}

	/**
	 * Everything Bean Validation and the JSON parser can reject, including the empty body and
	 * malformed JSON. None of it reaches the service, so a bad body can never half-register a device.
	 */
	@ParameterizedTest(name = "body={0}")
	@MethodSource("invalidCreateBodies")
	void create_shouldReturn400_whenTheBodyIsInvalid(String body) throws Exception {
		mockMvc.perform(post("/feeders").header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(feederService);
	}

	// --- Listing ------------------------------------------------------------------------------

	/**
	 * The list is a bare array of the caller's feeders, mapped from the entities and without the
	 * owner, which is what the service was asked for by token subject.
	 */
	@Test
	void list_shouldReturnTheFeedersOfTheTokenOwner() throws Exception {
		when(feederService.list(USER_ID)).thenReturn(List.of(feeder()));

		mockMvc.perform(get("/feeders").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(FEEDER_ID.toString()))
				.andExpect(jsonPath("$[0].deviceId").value(DEVICE_ID.toString()))
				.andExpect(jsonPath("$[0].name").value("Breakfast feeder"))
				.andExpect(jsonPath("$[0].user").doesNotExist())
				.andExpect(jsonPath("$[0].userId").doesNotExist())
				.andExpect(jsonPath("$[0].googleId").doesNotExist());

		verify(feederService).list(USER_ID);
		verifyNoMoreInteractions(feederService);
	}

	/** No envelope: an empty account is an empty array, not a 404 and not a wrapper object. */
	@Test
	void list_shouldReturnAnEmptyArray_whenTheCallerHasNoFeeders() throws Exception {
		when(feederService.list(USER_ID)).thenReturn(List.of());

		mockMvc.perform(get("/feeders").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$").isEmpty());

		verify(feederService).list(USER_ID);
		verifyNoMoreInteractions(feederService);
	}

	// --- Reading one --------------------------------------------------------------------------

	@Test
	void get_shouldReturnTheFeederOfTheTokenOwner() throws Exception {
		when(feederService.get(USER_ID, FEEDER_ID)).thenReturn(feeder());

		mockMvc.perform(get("/feeders/{id}", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(FEEDER_ID.toString()))
				.andExpect(jsonPath("$.name").value("Breakfast feeder"))
				.andExpect(jsonPath("$.user").doesNotExist())
				.andExpect(jsonPath("$.googleId").doesNotExist());

		verify(feederService).get(USER_ID, FEEDER_ID);
		verifyNoMoreInteractions(feederService);
	}

	/** Another owner's feeder and a missing one are indistinguishable here: one 404, one fixed detail. */
	@Test
	void get_shouldReturn404_whenTheFeederIsNotTheCallers() throws Exception {
		when(feederService.get(USER_ID, FEEDER_ID)).thenThrow(new FeederNotFoundException(FEEDER_ID));

		mockMvc.perform(get("/feeders/{id}", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"));

		verify(feederService).get(USER_ID, FEEDER_ID);
	}

	// --- Renaming -----------------------------------------------------------------------------

	/**
	 * An omitted description travels as null, which the domain turns into "cleared". A
	 * {@code deviceId} or an {@code active} smuggled into the body is ignored too: the pairing and
	 * the state are immutable here, and the response keeps what the entity already had.
	 */
	@Test
	void update_shouldReturnTheRenamedFeeder() throws Exception {
		Feeder renamed = feeder();
		renamed.update("Evening feeder", null);
		when(feederService.update(USER_ID, FEEDER_ID, "Evening feeder", null)).thenReturn(renamed);

		mockMvc.perform(put("/feeders/{id}", FEEDER_ID).header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Evening feeder\",\"deviceId\":\"" + OTHER_DEVICE_ID
								+ "\",\"active\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Evening feeder"))
				.andExpect(jsonPath("$.description").value(nullValue()))
				.andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
				.andExpect(jsonPath("$.active").value(true));

		verify(feederService).update(USER_ID, FEEDER_ID, "Evening feeder", null);
		verifyNoMoreInteractions(feederService);
	}

	@Test
	void update_shouldReturn404_whenTheFeederIsNotTheCallers() throws Exception {
		when(feederService.update(USER_ID, FEEDER_ID, "Evening feeder", "Porch"))
				.thenThrow(new FeederNotFoundException(FEEDER_ID));

		mockMvc.perform(put("/feeders/{id}", FEEDER_ID).header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"));

		verify(feederService).update(USER_ID, FEEDER_ID, "Evening feeder", "Porch");
	}

	@ParameterizedTest(name = "body={0}")
	@MethodSource("invalidUpdateBodies")
	void update_shouldReturn400_whenTheBodyIsInvalid(String body) throws Exception {
		mockMvc.perform(put("/feeders/{id}", FEEDER_ID).header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(feederService);
	}

	// --- Changing the zone --------------------------------------------------------------------

	@Test
	void updateTimezone_shouldReturnTheFeederInTheNewZone() throws Exception {
		when(feederService.updateTimezone(USER_ID, FEEDER_ID, "America/Manaus")).thenReturn(feederInManaus());

		mockMvc.perform(put("/feeders/{id}/timezone", FEEDER_ID).header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(TIMEZONE_BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timezone").value("America/Manaus"));

		verify(feederService).updateTimezone(USER_ID, FEEDER_ID, "America/Manaus");
		verifyNoMoreInteractions(feederService);
	}

	@Test
	void updateTimezone_shouldReturn404_whenTheFeederIsNotTheCallers() throws Exception {
		when(feederService.updateTimezone(USER_ID, FEEDER_ID, "America/Manaus"))
				.thenThrow(new FeederNotFoundException(FEEDER_ID));

		mockMvc.perform(put("/feeders/{id}/timezone", FEEDER_ID).header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(TIMEZONE_BODY))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"));

		verify(feederService).updateTimezone(USER_ID, FEEDER_ID, "America/Manaus");
	}

	/**
	 * Blank and oversized zones are rejected before the service. An unknown but well-formed zone is
	 * a separate case: it passes the DTO and is rejected by the domain, covered by
	 * {@code updateTimezone_shouldReturn400_whenTheDomainRejectsTheZone}.
	 */
	@ParameterizedTest(name = "body={0}")
	@MethodSource("invalidTimezoneBodies")
	void updateTimezone_shouldReturn400_whenTheBodyIsInvalid(String body) throws Exception {
		mockMvc.perform(put("/feeders/{id}/timezone", FEEDER_ID).header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(feederService);
	}

	/** The zone that got past the DTO and was rejected by the domain answers the same fixed 400. */
	@Test
	void updateTimezone_shouldReturn400_whenTheDomainRejectsTheZone() throws Exception {
		when(feederService.updateTimezone(USER_ID, FEEDER_ID, INVALID_TIMEZONE)).thenThrow(invalidTimezone());

		mockMvc.perform(put("/feeders/{id}/timezone", FEEDER_ID).header("Authorization", bearer())
						.contentType(MediaType.APPLICATION_JSON).content(timezoneBody(INVALID_TIMEZONE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("invalid request content"))
				.andExpect(content().string(not(containsString(INVALID_TIMEZONE))));

		verify(feederService).updateTimezone(USER_ID, FEEDER_ID, INVALID_TIMEZONE);
	}

	// --- Switching on and off -----------------------------------------------------------------

	@Test
	void activate_shouldReturnTheActivatedFeeder() throws Exception {
		when(feederService.activate(USER_ID, FEEDER_ID)).thenReturn(feeder());

		mockMvc.perform(post("/feeders/{id}/activate", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		verify(feederService).activate(USER_ID, FEEDER_ID);
		verifyNoMoreInteractions(feederService);
	}

	@Test
	void activate_shouldReturn404_whenTheFeederIsNotTheCallers() throws Exception {
		when(feederService.activate(USER_ID, FEEDER_ID)).thenThrow(new FeederNotFoundException(FEEDER_ID));

		mockMvc.perform(post("/feeders/{id}/activate", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"));

		verify(feederService).activate(USER_ID, FEEDER_ID);
	}

	@Test
	void deactivate_shouldReturnTheDeactivatedFeeder() throws Exception {
		Feeder inactive = feeder();
		inactive.deactivate();
		when(feederService.deactivate(USER_ID, FEEDER_ID)).thenReturn(inactive);

		mockMvc.perform(post("/feeders/{id}/deactivate", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false));

		verify(feederService).deactivate(USER_ID, FEEDER_ID);
		verifyNoMoreInteractions(feederService);
	}

	@Test
	void deactivate_shouldReturn404_whenTheFeederIsNotTheCallers() throws Exception {
		when(feederService.deactivate(USER_ID, FEEDER_ID)).thenThrow(new FeederNotFoundException(FEEDER_ID));

		mockMvc.perform(post("/feeders/{id}/deactivate", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"));

		verify(feederService).deactivate(USER_ID, FEEDER_ID);
	}

	// --- Removing -----------------------------------------------------------------------------

	/** 204 with an actually empty body, so a client does not have to ignore a stale payload. */
	@Test
	void delete_shouldReturn204_withNoBody() throws Exception {
		mockMvc.perform(delete("/feeders/{id}", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(feederService).delete(USER_ID, FEEDER_ID);
		verifyNoMoreInteractions(feederService);
	}

	/** Deleting another owner's feeder ends in the same 404 as everything else; the service checks. */
	@Test
	void delete_shouldReturn404_whenTheFeederIsNotTheCallers() throws Exception {
		doThrow(new FeederNotFoundException(FEEDER_ID)).when(feederService).delete(USER_ID, FEEDER_ID);

		mockMvc.perform(delete("/feeders/{id}", FEEDER_ID).header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"));

		verify(feederService).delete(USER_ID, FEEDER_ID);
	}

	// --- Matrices -----------------------------------------------------------------------------

	/** Nothing is public: without a token the filter answers before any body is parsed. */
	@ParameterizedTest(name = "{0}")
	@MethodSource("protectedRoutes")
	void routes_shouldReturn401_whenThereIsNoToken(RequestBuilder request) throws Exception {
		mockMvc.perform(request)
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(feederService);
	}

	/**
	 * A path id that is not a UUID is a malformed request, not a missing feeder. The response is a
	 * problem document, not the HTML error page the framework falls back to when it has no body:
	 * the client can parse it, and the echoed value cannot be rendered as markup.
	 */
	@ParameterizedTest(name = "{0}")
	@MethodSource("routesWithAnId")
	void routesWithAnId_shouldReturn400_whenTheIdIsNotAUuid(MockHttpServletRequestBuilder request) throws Exception {
		mockMvc.perform(request.header("Authorization", bearer()))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));

		verifyNoInteractions(feederService);
	}

	/**
	 * There is no route that names a user, not even the caller's own: the owner always comes from the
	 * token, so a route that takes one simply does not exist and never reaches the service.
	 */
	@Test
	void routes_shouldReturn404_whenThePathNamesAUser() throws Exception {
		mockMvc.perform(get("/feeders/{userId}/feeders", OTHER_USER_ID).header("Authorization", bearer()))
				.andExpect(status().isNotFound());

		verifyNoInteractions(feederService);
	}

	// --- Helpers ------------------------------------------------------------------------------

	private static Stream<String> invalidCreateBodies() {
		return Stream.of(
				"",                                                                          // missing body
				"{}",                                                                        // deviceId and name missing
				"{\"name\":\"Breakfast feeder\"}",                                            // deviceId missing
				"{\"deviceId\":\"" + DEVICE_ID + "\"}",                                       // name missing
				"{\"deviceId\":\"not-a-uuid\",\"name\":\"Breakfast feeder\"}",                // deviceId not a UUID
				"{\"deviceId\":\"" + DEVICE_ID + "\",\"name\":\"   \"}",                      // blank name
				"{\"deviceId\":\"" + DEVICE_ID + "\",\"name\":\"Breakfast feeder\",\"timezone\":\""
						+ "a".repeat(65) + "\"}",                                            // timezone > 64
				"not json");                                                                  // malformed JSON
	}

	private static Stream<String> invalidUpdateBodies() {
		return Stream.of(
				"",
				"{}",                                                        // name missing
				"{\"name\":\"   \"}",                                        // blank name
				"{\"name\":\"" + "a".repeat(256) + "\"}",
				"{\"name\":\"Evening feeder\",\"description\":\"" + "a".repeat(256) + "\"}",
				"not json");
	}

	private static Stream<String> invalidTimezoneBodies() {
		return Stream.of(
				"",
				"{}",                                    // timezone missing
				"{\"timezone\":\"\"}",                   // @NotBlank
				"{\"timezone\":\"   \"}",                // @NotBlank
				"{\"timezone\":\"" + "a".repeat(65) + "\"}",
				"not json");
	}

	private static Stream<RequestBuilder> protectedRoutes() {
		return Stream.of(
				post("/feeders").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY),
				get("/feeders"),
				get("/feeders/{id}", FEEDER_ID),
				put("/feeders/{id}", FEEDER_ID).contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY),
				put("/feeders/{id}/timezone", FEEDER_ID).contentType(MediaType.APPLICATION_JSON)
						.content(TIMEZONE_BODY),
				post("/feeders/{id}/activate", FEEDER_ID),
				post("/feeders/{id}/deactivate", FEEDER_ID),
				delete("/feeders/{id}", FEEDER_ID));
	}

	private static Stream<MockHttpServletRequestBuilder> routesWithAnId() {
		return Stream.of(
				get("/feeders/not-a-uuid"),
				put("/feeders/not-a-uuid").contentType(MediaType.APPLICATION_JSON).content(UPDATE_BODY),
				put("/feeders/not-a-uuid/timezone").contentType(MediaType.APPLICATION_JSON)
						.content(TIMEZONE_BODY),
				post("/feeders/not-a-uuid/activate"),
				post("/feeders/not-a-uuid/deactivate"),
				delete("/feeders/not-a-uuid"));
	}

	private String bearer() {
		return "Bearer " + jwtService.issue(user()).value();
	}

	/** User has no setters and no id until persisted, so the id is placed by hand. */
	private static User user() {
		User user = User.create("google-abc", "tiago@example.com", "Tiago");
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}

	/**
	 * Feeder has no setters and no id or audit columns until persisted, so they are placed by hand.
	 * The id matters twice: the response body and the {@code Location} header are built from it.
	 */
	private static Feeder feeder() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Breakfast feeder", "Kitchen", user());
		ReflectionTestUtils.setField(feeder, "id", FEEDER_ID);
		ReflectionTestUtils.setField(feeder, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(feeder, "updatedAt", UPDATED_AT);
		return feeder;
	}

	/** The same feeder after a zone change, built through the domain so the value stays validated. */
	private static Feeder feederInManaus() {
		Feeder feeder = feeder();
		feeder.updateTimezone("America/Manaus");
		return feeder;
	}

	/** What the service throws when the domain rejects a zone: the value survives only in the cause. */
	private static InvalidFeederDataException invalidTimezone() {
		IllegalArgumentException cause =
				new IllegalArgumentException("timezone must be a valid zone id: " + INVALID_TIMEZONE);
		return new InvalidFeederDataException(cause.getMessage(), cause);
	}

	private static String timezoneBody(String timezone) {
		return "{\"timezone\":\"" + timezone + "\"}";
	}
}
