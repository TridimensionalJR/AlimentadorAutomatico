package com.threedimensional.feeder;

import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.FeederConfig;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.repository.FeederConfigRepository;
import com.threedimensional.feeder.repository.FeederRepository;
import com.threedimensional.feeder.repository.UserRepository;
import com.threedimensional.feeder.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The feeder lifecycle end to end: real HTTP through {@link MockMvc}, the real filter chain, real
 * tokens issued by {@link JwtService}, the Flyway migrations on H2, real transactions and the real
 * advice. Nothing is mocked here on purpose - the slice tests prove each layer's contract against
 * stubs, and this class proves the layers actually agree with each other and with the database.
 * <p>
 * Deliberately not {@code @Transactional}: the race test needs two transactions committed on two
 * different threads, and every request runs on the calling thread but in its own transaction, so a
 * rollback at the end of a test would not undo what the requests wrote. The database is therefore
 * emptied before each test in dependency order (configs, feeders, users) - the schema's
 * {@code RESTRICT} forbids deleting a user that still owns a feeder, which is itself a contract
 * proved below through {@code DELETE /me}.
 * <p>
 * Identities are persisted accounts: the token subject is the row id, so a token here is one the
 * application would genuinely issue after a Google sign-in.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class FeederRegistrationIntegrationTests {

	private static final String AUTHORIZATION = "Authorization";
	private static final String FEEDER_NAME = "Alimentador da sala";
	private static final String DEFAULT_TIMEZONE = "America/Bahia";
	/** Well-formed but unknown to the JVM's zone database, so the domain rejects it. */
	private static final String INVALID_TIMEZONE = "Mars/Olympus";
	private static final UUID DEVICE_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
	private static final UUID OTHER_DEVICE_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
	private static final UUID THIRD_DEVICE_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
	/** No feeder will ever have this id, which is what the anti-oracle comparison needs. */
	private static final UUID MISSING_FEEDER_ID = UUID.fromString("deadbeef-dead-beef-dead-beefdeadbeef");
	private static final long TIMEOUT_SECONDS = 20;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private FeederRepository feederRepository;

	@Autowired
	private FeederConfigRepository feederConfigRepository;

	private User ana;

	private User bruno;

	private String anaToken;

	private String brunoToken;

	@BeforeEach
	void resetDatabaseAndSignInTwoAccounts() {
		feederConfigRepository.deleteAll();
		feederRepository.deleteAll();
		userRepository.deleteAll();

		ana = userRepository.save(User.create("google-ana", "ana@example.com", "Ana"));
		bruno = userRepository.save(User.create("google-bruno", "bruno@example.com", "Bruno"));
		anaToken = bearer(ana);
		brunoToken = bearer(bruno);
	}

	// --- The whole lifecycle ------------------------------------------------------------------

	/**
	 * One feeder through every route it has, asserting the state each response promises and that
	 * the next request sees the change. The zone change after the rename proves the rename did not
	 * touch the rest of the row, and the final 404 proves the delete really removed it.
	 */
	@Test
	void fullLifecycle_shouldCreateListGetRenameMoveZoneToggleAndDelete() throws Exception {
		MvcResult created = mockMvc.perform(post("/feeders")
						.header(AUTHORIZATION, anaToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(DEVICE_ID, FEEDER_NAME, "Perto da janela", null)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", matchesPattern(
						"/feeders/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
				.andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
				.andExpect(jsonPath("$.name").value(FEEDER_NAME))
				.andExpect(jsonPath("$.description").value("Perto da janela"))
				.andExpect(jsonPath("$.timezone").value(DEFAULT_TIMEZONE))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.createdAt").isNotEmpty())
				.andExpect(jsonPath("$.updatedAt").isNotEmpty())
				.andExpect(jsonPath("$.user").doesNotExist())
				.andExpect(jsonPath("$.userId").doesNotExist())
				.andReturn();

		UUID feederId = createdId(created);
		assertThat(created.getResponse().getHeader("Location")).isEqualTo("/feeders/" + feederId);

		mockMvc.perform(get("/feeders").header(AUTHORIZATION, anaToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[0].id").value(feederId.toString()));

		mockMvc.perform(get("/feeders/{id}", feederId).header(AUTHORIZATION, anaToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(feederId.toString()))
				.andExpect(jsonPath("$.name").value(FEEDER_NAME));

		mockMvc.perform(put("/feeders/{id}", feederId)
						.header(AUTHORIZATION, anaToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(updateBody("Alimentador da cozinha", null)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(feederId.toString()))
				.andExpect(jsonPath("$.name").value("Alimentador da cozinha"))
				.andExpect(jsonPath("$.description").value(nullValue()))
				.andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()));

		mockMvc.perform(put("/feeders/{id}/timezone", feederId)
						.header(AUTHORIZATION, anaToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(timezoneBody("America/Manaus")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timezone").value("America/Manaus"))
				.andExpect(jsonPath("$.name").value("Alimentador da cozinha"));

		mockMvc.perform(post("/feeders/{id}/deactivate", feederId).header(AUTHORIZATION, anaToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.timezone").value("America/Manaus"));

		mockMvc.perform(post("/feeders/{id}/activate", feederId).header(AUTHORIZATION, anaToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true));

		mockMvc.perform(delete("/feeders/{id}", feederId).header(AUTHORIZATION, anaToken))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		mockMvc.perform(get("/feeders/{id}", feederId).header(AUTHORIZATION, anaToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"));
	}

	// --- Isolation by id ----------------------------------------------------------------------

	/*
	 * Each mutation is attempted by the other account and has two halves: the 404 the intruder
	 * gets, and the state the owner still sees afterwards. A 404 alone would not prove the write
	 * was refused - the response could be right and the row already changed.
	 */

	@Test
	void isolation_shouldReturn404_whenAnotherUserReadsTheFeeder() throws Exception {
		UUID feederId = createFeederForAna();

		assertFeederNotFound(mockMvc.perform(get("/feeders/{id}", feederId)
				.header(AUTHORIZATION, brunoToken)));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	@Test
	void isolation_shouldReturn404_whenAnotherUserRenames() throws Exception {
		UUID feederId = createFeederForAna();

		assertFeederNotFound(mockMvc.perform(put("/feeders/{id}", feederId)
				.header(AUTHORIZATION, brunoToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(updateBody("Alimentador do invasor", "tomado"))));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	@Test
	void isolation_shouldReturn404_whenAnotherUserMovesTheZone() throws Exception {
		UUID feederId = createFeederForAna();

		assertFeederNotFound(mockMvc.perform(put("/feeders/{id}/timezone", feederId)
				.header(AUTHORIZATION, brunoToken)
				.contentType(MediaType.APPLICATION_JSON)
				.content(timezoneBody("Europe/Lisbon"))));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	/**
	 * The feeder is deactivated first, on purpose: an already-active feeder would answer the same
	 * 200 whether the intruder flipped it or not. Leaving it off proves the activate was refused.
	 */
	@Test
	void isolation_shouldReturn404_whenAnotherUserActivates() throws Exception {
		UUID feederId = createFeederForAna();
		mockMvc.perform(post("/feeders/{id}/deactivate", feederId).header(AUTHORIZATION, anaToken))
				.andExpect(status().isOk());

		assertFeederNotFound(mockMvc.perform(post("/feeders/{id}/activate", feederId)
				.header(AUTHORIZATION, brunoToken)));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, false);
	}

	@Test
	void isolation_shouldReturn404_whenAnotherUserDeactivates() throws Exception {
		UUID feederId = createFeederForAna();

		assertFeederNotFound(mockMvc.perform(post("/feeders/{id}/deactivate", feederId)
				.header(AUTHORIZATION, brunoToken)));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	@Test
	void isolation_shouldReturn404_whenAnotherUserDeletes() throws Exception {
		UUID feederId = createFeederForAna();

		assertFeederNotFound(mockMvc.perform(delete("/feeders/{id}", feederId)
				.header(AUTHORIZATION, brunoToken)));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	/**
	 * The anti-oracle guarantee: the response for somebody else's feeder and the response for an
	 * id that never existed are compared as whole bodies, not field by field, so no extra property
	 * could slip in and distinguish them. Same status, same content type, and byte-identical once
	 * {@code instance} is normalised - that one field is the caller's own request URI, which the
	 * caller already knows and which therefore says nothing about whether the row exists. Nothing
	 * else in the body is allowed to differ.
	 */
	@Test
	void isolation_shouldAnswerExactlyLikeAMissingFeeder_whenTheFeederBelongsToAnotherUser() throws Exception {
		UUID feederId = createFeederForAna();

		MvcResult foreign = mockMvc.perform(get("/feeders/{id}", feederId)
						.header(AUTHORIZATION, brunoToken))
				.andReturn();
		MvcResult missing = mockMvc.perform(get("/feeders/{id}", MISSING_FEEDER_ID)
						.header(AUTHORIZATION, brunoToken))
				.andReturn();

		String foreignBody = foreign.getResponse().getContentAsString();
		String missingBody = missing.getResponse().getContentAsString();

		assertThat(foreign.getResponse().getStatus()).isEqualTo(404);
		assertThat(missing.getResponse().getStatus()).isEqualTo(404);
		assertThat(foreign.getResponse().getContentType()).isEqualTo(missing.getResponse().getContentType());
		assertThat(foreignBody).contains("\"instance\":\"/feeders/" + feederId + "\"");
		assertThat(missingBody).contains("\"instance\":\"/feeders/" + MISSING_FEEDER_ID + "\"");
		assertThat(withoutRequestUri(foreignBody))
				.as("404 body for another account's feeder")
				.isEqualTo(withoutRequestUri(missingBody));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	/** Blanks the only body field that necessarily echoes the request, so the rest can be compared. */
	private static String withoutRequestUri(String body) {
		return body.replaceAll("\"instance\":\"[^\"]*\"", "\"instance\":\"<request-uri>\"");
	}

	// --- Isolation of the list (the widest leak surface) --------------------------------------

	/**
	 * The list is asserted as a whole body, not only by size: an empty account must literally
	 * receive {@code []}, and each owner's list must contain exactly its own ids and none of the
	 * other's, including the raw text of the response - a stray field carrying the other account's
	 * id would fail here even if the ids matched by count.
	 */
	@Test
	void list_shouldNotLeakAnotherUsersFeeder() throws Exception {
		UUID anaFirst = createFeederForAna();

		mockMvc.perform(get("/feeders").header(AUTHORIZATION, brunoToken))
				.andExpect(status().isOk())
				.andExpect(content().string("[]"));

		UUID anaSecond = createFeederForAna(OTHER_DEVICE_ID, "Alimentador do quintal");
		UUID brunoFirst = createFeeder(brunoToken, THIRD_DEVICE_ID, "Alimentador do escritorio");

		mockMvc.perform(get("/feeders").header(AUTHORIZATION, anaToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(2)))
				.andExpect(jsonPath("$[*].id", containsInAnyOrder(anaFirst.toString(), anaSecond.toString())))
				.andExpect(content().string(not(containsString(brunoFirst.toString()))))
				.andExpect(content().string(not(containsString(THIRD_DEVICE_ID.toString()))));

		mockMvc.perform(get("/feeders").header(AUTHORIZATION, brunoToken))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(1)))
				.andExpect(jsonPath("$[*].id", containsInAnyOrder(brunoFirst.toString())))
				.andExpect(content().string(not(containsString(anaFirst.toString()))))
				.andExpect(content().string(not(containsString(anaSecond.toString()))));
	}

	/**
	 * A token survives the account it names. The list needs no account row, so it answers an empty
	 * array; {@code /me} is where the dead session becomes visible, which is the signal the front
	 * keys on.
	 */
	@Test
	void list_shouldReturnEmptyArray_whenTheAccountWasDeletedWithALiveToken() throws Exception {
		userRepository.delete(ana);

		mockMvc.perform(get("/feeders").header(AUTHORIZATION, anaToken))
				.andExpect(status().isOk())
				.andExpect(content().string("[]"));

		mockMvc.perform(get("/me").header(AUTHORIZATION, anaToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("user not found"));
	}

	// --- Device pairing is global -------------------------------------------------------------

	/**
	 * The UNIQUE constraint is on the table, not on the owner: a device paired to one account is
	 * refused to every other, and the rejection changes nothing about the existing row.
	 */
	@Test
	void create_shouldReturn409_whenTheDeviceBelongsToAnotherAccount() throws Exception {
		UUID feederId = createFeederForAna();

		mockMvc.perform(post("/feeders")
						.header(AUTHORIZATION, brunoToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(DEVICE_ID, "Alimentador do invasor", null, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("device already registered"))
				.andExpect(content().string(not(containsString(DEVICE_ID.toString()))))
				.andExpect(content().string(not(containsString(ana.getId().toString()))));

		assertThat(feederRepository.findByIdAndUserId(feederId, ana.getId())).isPresent();
		assertThat(feederRepository.findByIdAndUserId(feederId, bruno.getId())).isEmpty();
		assertThat(feederRepository.findAll()).hasSize(1);
		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	@Test
	void create_shouldReturn409_whenTheSameAccountRegistersTheSameDeviceAgain() throws Exception {
		UUID feederId = createFeederForAna();

		mockMvc.perform(post("/feeders")
						.header(AUTHORIZATION, anaToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(DEVICE_ID, FEEDER_NAME, null, null)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("device already registered"));

		assertThat(feederRepository.findAll()).hasSize(1);
		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	// --- Bad input rejected by the domain -----------------------------------------------------

	/**
	 * An empty zone is not "absent": only null means the domain default, so the POST reaches the
	 * domain, is labelled by the service and answered with the fixed 400. The domain message stays in
	 * the log, and the transaction never commits, which is why no feeder exists afterwards.
	 */
	@Test
	void create_shouldReturn400_whenTheTimezoneIsBlank() throws Exception {
		mockMvc.perform(post("/feeders")
						.header(AUTHORIZATION, anaToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(DEVICE_ID, FEEDER_NAME, null, "")))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value("invalid request content"))
				.andExpect(content().string(not(containsString("timezone must not be blank"))));

		assertThat(feederRepository.findAll()).isEmpty();
	}

	/** Same path for a zone that is well-formed but unknown, and the rejected value is not echoed. */
	@Test
	void create_shouldReturn400_whenTheTimezoneIsUnknown() throws Exception {
		mockMvc.perform(post("/feeders")
						.header(AUTHORIZATION, anaToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(DEVICE_ID, FEEDER_NAME, null, INVALID_TIMEZONE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("invalid request content"))
				.andExpect(content().string(not(containsString(INVALID_TIMEZONE))));

		assertThat(feederRepository.findAll()).isEmpty();
	}

	/**
	 * The zone change fails before the commit, so the old zone survives: the 400 is not just a status,
	 * the rollback is what keeps a rejected value out of the row.
	 */
	@Test
	void updateTimezone_shouldReturn400_whenTheZoneIsUnknown() throws Exception {
		UUID feederId = createFeederForAna();

		mockMvc.perform(put("/feeders/{id}/timezone", feederId)
						.header(AUTHORIZATION, anaToken)
						.contentType(MediaType.APPLICATION_JSON)
						.content(timezoneBody(INVALID_TIMEZONE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("invalid request content"))
				.andExpect(content().string(not(containsString(INVALID_TIMEZONE))));

		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	// --- The race on the same device ----------------------------------------------------------

	/**
	 * The pre-check cannot decide a race, so the UNIQUE constraint has to, and the service's flush
	 * is what turns the loser's violation into the same 409 instead of a 500 at commit. Two real
	 * accounts, one device, two threads released together: exactly one 201, one 409, one row.
	 * <p>
	 * {@code Future.get} is bounded so a stuck request fails the test instead of hanging it, and
	 * {@code awaitTermination} in the {@code finally} means no thread outlives the test and leaks a
	 * transaction into the next one.
	 */
	@Test
	void create_shouldReturnExactlyOne201AndOne409_whenTwoUsersRaceForTheSameDevice() throws Exception {
		String anaBody = createBody(DEVICE_ID, "Alimentador da sala", null, null);
		String brunoBody = createBody(DEVICE_ID, "Alimentador do quarto", null, null);
		CyclicBarrier startLine = new CyclicBarrier(2);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		List<Integer> statuses;
		try {
			Future<Integer> anaAttempt = executor.submit(() -> createAfter(startLine, anaToken, anaBody));
			Future<Integer> brunoAttempt = executor.submit(() -> createAfter(startLine, brunoToken, brunoBody));
			statuses = List.of(anaAttempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS),
					brunoAttempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
		} finally {
			executor.shutdown();
			assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(statuses).doesNotContain(500);
		assertThat(statuses).containsExactlyInAnyOrder(201, 409);
		assertThat(feederRepository.findAll())
				.extracting(Feeder::getDeviceId)
				.containsExactly(DEVICE_ID);
	}

	// --- Cascade ------------------------------------------------------------------------------

	/**
	 * The schedules disappear with their feeder through the FK's {@code ON DELETE CASCADE}. The
	 * assertion reads the configs table directly: a response only proves the feeder is gone, and
	 * the point is that no orphan rule survives it.
	 */
	@Test
	void delete_shouldRemoveTheConfigs_whenTheFeederHasSchedules() throws Exception {
		UUID feederId = createFeederForAna();
		Feeder feeder = feederRepository.findById(feederId).orElseThrow();
		feederConfigRepository.saveAndFlush(FeederConfig.createInterval(feeder, 30, 2));

		assertThat(feederConfigRepository.findByFeederId(feederId)).hasSize(1);

		mockMvc.perform(delete("/feeders/{id}", feederId).header(AUTHORIZATION, anaToken))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		assertThat(feederConfigRepository.findByFeederId(feederId)).isEmpty();
	}

	// --- Deleting the account -----------------------------------------------------------------

	/** The RESTRICT behind the 409: the account and its feeder are still there afterwards. */
	@Test
	void deleteMe_shouldReturn409_whenTheAccountStillOwnsAFeeder() throws Exception {
		UUID feederId = createFeederForAna();

		mockMvc.perform(delete("/me").header(AUTHORIZATION, anaToken))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail")
						.value("the account still has feeders: remove them before deleting it"));

		assertThat(userRepository.findById(ana.getId())).isPresent();
		assertFeederState(anaToken, feederId, FEEDER_NAME, DEFAULT_TIMEZONE, true);
	}

	/** Without feeders the account goes, and the live token stops naming anything. */
	@Test
	void deleteMe_shouldReturn204_whenTheAccountHasNoFeeders() throws Exception {
		mockMvc.perform(delete("/me").header(AUTHORIZATION, anaToken))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		mockMvc.perform(get("/me").header(AUTHORIZATION, anaToken))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("user not found"));
	}

	// --- Helpers ------------------------------------------------------------------------------

	/** A token this application really issued, exactly as sign-in would. */
	private String bearer(User user) {
		return "Bearer " + jwtService.issue(user).value();
	}

	private UUID createFeederForAna() throws Exception {
		return createFeeder(anaToken, DEVICE_ID, FEEDER_NAME);
	}

	private UUID createFeederForAna(UUID deviceId, String name) throws Exception {
		return createFeeder(anaToken, deviceId, name);
	}

	/** Registers a feeder through the API and returns the id from the {@code Location} it promised. */
	private UUID createFeeder(String bearer, UUID deviceId, String name) throws Exception {
		return createdId(mockMvc.perform(post("/feeders")
						.header(AUTHORIZATION, bearer)
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody(deviceId, name, null, null)))
				.andExpect(status().isCreated())
				.andReturn());
	}

	private static UUID createdId(MvcResult result) {
		String location = result.getResponse().getHeader("Location");
		assertThat(location).as("Location header of a created feeder").isNotNull();
		return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
	}

	/** The 404 every owner-scoped route answers for a feeder of another account. */
	private static void assertFeederNotFound(ResultActions response) throws Exception {
		response.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.detail").value("feeder not found"));
	}

	private void assertFeederState(String bearer, UUID feederId, String name, String timezone, boolean active)
			throws Exception {
		mockMvc.perform(get("/feeders/{id}", feederId).header(AUTHORIZATION, bearer))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(feederId.toString()))
				.andExpect(jsonPath("$.name").value(name))
				.andExpect(jsonPath("$.timezone").value(timezone))
				.andExpect(jsonPath("$.active").value(active));
	}

	private int createAfter(CyclicBarrier startLine, String bearer, String body) throws Exception {
		startLine.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
		return mockMvc.perform(post("/feeders")
						.header(AUTHORIZATION, bearer)
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andReturn().getResponse().getStatus();
	}

	/** Omitted parts are omitted in the JSON too, which is what "absent" means for the API. */
	private static String createBody(UUID deviceId, String name, String description, String timezone) {
		StringBuilder json = new StringBuilder("{\"deviceId\":\"").append(deviceId)
				.append("\",\"name\":\"").append(name).append('"');
		if (description != null) {
			json.append(",\"description\":\"").append(description).append('"');
		}
		if (timezone != null) {
			json.append(",\"timezone\":\"").append(timezone).append('"');
		}
		return json.append('}').toString();
	}

	private static String updateBody(String name, String description) {
		String json = "{\"name\":\"" + name + "\"";
		if (description != null) {
			json += ",\"description\":\"" + description + "\"";
		}
		return json + "}";
	}

	private static String timezoneBody(String timezone) {
		return "{\"timezone\":\"" + timezone + "\"}";
	}
}
