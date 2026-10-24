package com.threedimensional.feeder.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@link ApiExceptionHandler} alone: each mapped exception answers the status
 * and the fixed detail from the design, and nothing the exception message carries leaks into the
 * body. The stub controller only raises them, so no real controller or service is involved. One
 * test checks the messages themselves, because the ids are supposed to live there and nowhere else.
 * <p>
 * The log side is captured with a {@link ListAppender} attached to the advice's own logger, not
 * with {@code OutputCaptureExtension}: only this way the test can prove the level, the logger name
 * and that there is exactly one event, instead of one line of text among the framework's noise.
 * <p>
 * Filters are off because authentication is not what is under test, and the advice is picked up by
 * the slice itself (a {@code @ControllerAdvice} is part of the scanned web components), so
 * importing it would register it twice.
 */
@WebMvcTest(controllers = ApiExceptionHandlerStubController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiExceptionHandlerTests {

	/** Mirrors {@code ApiExceptionHandler#MAX_LOG_LENGTH}; the truncation is the behaviour under test. */
	private static final int MAX_LOG_LENGTH = 200;

	@Autowired
	private MockMvc mockMvc;

	/** Same reason as in {@code AuthControllerTests}: the application class enables JPA auditing. */
	@MockitoBean
	private JpaMetamodelMappingContext jpaMetamodelMappingContext;

	private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

	private final Logger adviceLogger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);

	@BeforeEach
	void attachLogs() {
		logs.list.clear();
		logs.start();
		adviceLogger.addAppender(logs);
	}

	@AfterEach
	void detachLogs() {
		adviceLogger.detachAppender(logs);
		logs.stop();
	}

	private void assertSingleInfoLine(String expected) {
		assertThat(logs.list).hasSize(1);
		ILoggingEvent event = logs.list.getFirst();
		assertThat(event.getLevel()).isEqualTo(Level.INFO);
		assertThat(event.getLoggerName()).isEqualTo(ApiExceptionHandler.class.getName());
		assertThat(event.getThrowableProxy()).isNull();
		assertThat(event.getFormattedMessage()).isEqualTo(expected);
	}

	/**
	 * A feeder of another account and one that never existed share this response, so the id cannot
	 * be used to probe for somebody else's device.
	 */
	@Test
	void feederNotFound_shouldReturn404_andNotEchoTheId() throws Exception {
		mockMvc.perform(get("/test/feeders/missing"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("feeder not found"))
				.andExpect(content().string(not(containsString(ApiExceptionHandlerStubController.FEEDER_ID.toString()))));
	}

	/**
	 * The caller learns the device is taken, not whose account holds it, and not the unique
	 * constraint that lost the race either: the body is the fixed sentence from DESIGN §8, so a
	 * database detail cannot reach the client through this handler.
	 */
	@Test
	void deviceAlreadyRegistered_shouldReturn409_andNotEchoTheDeviceId() throws Exception {
		mockMvc.perform(get("/test/devices/conflict"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("device already registered"))
				.andExpect(content().string(not(containsString(ApiExceptionHandlerStubController.DEVICE_ID.toString()))))
				.andExpect(content().string(not(containsString("uk_feeders_device_id"))));
	}

	/**
	 * A rejected domain value is a client error, and the message naming it stays in the process:
	 * echoing it would help an attacker map what the validator accepts.
	 */
	@Test
	void invalidFeederData_shouldReturn400_andNotEchoTheRejectedValue() throws Exception {
		mockMvc.perform(get("/test/invalid-content"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("invalid request content"))
				.andExpect(content().string(not(containsString(ApiExceptionHandlerStubController.INVALID_TIMEZONE))));
	}

	/**
	 * The other side of that decision: an IAE that no domain call labelled is a bug, so it must not
	 * be answered as a client error. MockMvc does not run the ERROR dispatch, so the unresolved
	 * exception reaches the test wrapped by {@code FrameworkServlet} in a {@code ServletException};
	 * on a real container the same path answers 500. What this proves is that it is no longer a 400.
	 */
	@Test
	void illegalArgument_shouldPropagate_whenNoDomainExceptionWrapsIt() {
		assertThatThrownBy(() -> mockMvc.perform(get("/test/illegal-argument")))
				.isInstanceOf(ServletException.class)
				.hasCauseInstanceOf(IllegalArgumentException.class);
	}

	/**
	 * The other half of the absence assertions above: the ids really are in the message, which is
	 * where the design keeps them for the log. If they ever left the message, the response could not
	 * leak them anymore, but the one place meant to identify the record would have lost them.
	 */
	@Test
	void exceptions_shouldKeepTheIdsInTheirMessage() {
		assertThat(new FeederNotFoundException(ApiExceptionHandlerStubController.FEEDER_ID).getMessage())
				.contains(ApiExceptionHandlerStubController.FEEDER_ID.toString());
		assertThat(new DeviceAlreadyRegisteredException(ApiExceptionHandlerStubController.DEVICE_ID).getMessage())
				.contains(ApiExceptionHandlerStubController.DEVICE_ID.toString());
	}

	/**
	 * The id is the one useful piece of the message, so it must reach the log now that the response
	 * cannot carry it. Exactly one line, at INFO: a 404 is expected flow, not an incident.
	 */
	@Test
	void feederNotFound_shouldLogOneInfoLine_whenMapped() throws Exception {
		mockMvc.perform(get("/test/feeders/missing"))
				.andExpect(status().isNotFound());

		assertSingleInfoLine("Feeder not found: feeder " + ApiExceptionHandlerStubController.FEEDER_ID + " does not exist");
	}

	/** A token that outlived its account answers 404 and leaves the account id in the log. */
	@Test
	void userNotFound_shouldLogOneInfoLine_whenMapped() throws Exception {
		mockMvc.perform(get("/test/users/missing"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("user not found"))
				.andExpect(content().string(not(containsString(ApiExceptionHandlerStubController.USER_ID.toString()))));

		assertSingleInfoLine("User not found: user " + ApiExceptionHandlerStubController.USER_ID + " does not exist");
	}

	/** Account deletion refused because of feeders: the id stays out of the body and goes to the log. */
	@Test
	void userHasFeeders_shouldLogOneInfoLine_whenMapped() throws Exception {
		mockMvc.perform(get("/test/users/with-feeders"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail")
						.value("the account still has feeders: remove them before deleting it"))
				.andExpect(content().string(not(containsString(ApiExceptionHandlerStubController.USER_ID.toString()))));

		assertSingleInfoLine("User still owns feeders: user " + ApiExceptionHandlerStubController.USER_ID
				+ " still owns feeders and cannot be deleted");
	}

	/** The device id explains a duplicate registration to whoever reads the log, never to the caller. */
	@Test
	void deviceAlreadyRegistered_shouldLogOneInfoLine_whenMapped() throws Exception {
		mockMvc.perform(get("/test/devices/conflict"))
				.andExpect(status().isConflict());

		assertSingleInfoLine("Device already registered: device " + ApiExceptionHandlerStubController.DEVICE_ID
				+ " is already registered");
	}

	/** The rejected value is what makes a 400 diagnosable, so it is logged as the domain named it. */
	@Test
	void invalidFeederData_shouldLogOneInfoLine_whenMapped() throws Exception {
		mockMvc.perform(get("/test/invalid-content"))
				.andExpect(status().isBadRequest());

		assertSingleInfoLine("Invalid feeder data: Unknown time-zone ID: "
				+ ApiExceptionHandlerStubController.INVALID_TIMEZONE);
	}

	/**
	 * Client input can carry a line break, so an unsanitized message would let the caller write a
	 * forged entry into the log. Every control character (C0 and C1, so NEL too) and every Unicode
	 * line/paragraph separator becomes {@code _}, the forged text stays inert on the same line, and
	 * the response keeps saying nothing about the rejected value.
	 */
	@Test
	void invalidFeederData_shouldLogASingleLine_whenMessageCarriesCrLfAndForgedText() throws Exception {
		mockMvc.perform(get("/test/invalid-content/injected"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("invalid request content"))
				.andExpect(content().string(not(containsString("ERROR"))));

		assertSingleInfoLine("Invalid feeder data: Unknown time-zone ID: Mars/Olympus__ERROR_forged_line_entry");
		assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("\n", "\r", "\u0085", "\u2028", "\u2029");
	}

	/** One hostile request must not be able to flood the log, so the message is cut at the limit. */
	@Test
	void invalidFeederData_shouldTruncateTheMessage_whenItIsLongerThanTheLimit() throws Exception {
		mockMvc.perform(get("/test/invalid-content/long"))
				.andExpect(status().isBadRequest());

		String truncated = ("Unknown time-zone ID: " + ApiExceptionHandlerStubController.LONG_REJECTED_VALUE)
				.substring(0, MAX_LOG_LENGTH) + "...";
		assertSingleInfoLine("Invalid feeder data: " + truncated);
	}

	/**
	 * The other side of the cut: a message exactly at the limit is logged whole, so the
	 * {@code ...} marker only ever means a real truncation and never eats a character of a message
	 * that fits.
	 */
	@Test
	void invalidFeederData_shouldKeepTheWholeMessage_whenItIsExactlyAtTheLimit() throws Exception {
		assertThat(ApiExceptionHandlerStubController.AT_LIMIT_MESSAGE).hasSize(MAX_LOG_LENGTH);

		mockMvc.perform(get("/test/invalid-content/at-limit"))
				.andExpect(status().isBadRequest());

		assertSingleInfoLine("Invalid feeder data: " + ApiExceptionHandlerStubController.AT_LIMIT_MESSAGE);
	}

	/**
	 * The helper documents a fallback for a null message, and a domain IAE without one can reach
	 * this handler: the log still gets a readable single line instead of the text {@code null}.
	 */
	@Test
	void invalidFeederData_shouldLogTheFallback_whenTheMessageIsNull() throws Exception {
		mockMvc.perform(get("/test/invalid-content/no-message"))
				.andExpect(status().isBadRequest());

		assertSingleInfoLine("Invalid feeder data: <no message>");
	}

	/**
	 * The advice never touches the request, so the credential in the header and the e-mail and name
	 * in the body stay out of the line even though the stub read the body before failing. Exact
	 * equality is what proves it: the line is the fixed prefix plus the exception message, nothing
	 * else.
	 */
	@Test
	void feederNotFound_shouldNotLogTheRequest_whenItCarriesACredentialAndPersonalData() throws Exception {
		String body = "{\"email\":\"qa@example.com\",\"name\":\"Sensitive Name\"}";

		mockMvc.perform(post("/test/feeders/missing-with-body")
						.header("Authorization", "Bearer sentinel-credential")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body))
				.andExpect(status().isNotFound());

		assertSingleInfoLine("Feeder not found: feeder " + ApiExceptionHandlerStubController.FEEDER_ID
				+ " does not exist");
	}

	/**
	 * The verifier already logged the reason with a {@code WARN} when it rejected the token; logging
	 * it here again would report one rejection twice.
	 */
	@Test
	void invalidGoogleToken_shouldNotLog_whenMapped() throws Exception {
		mockMvc.perform(get("/test/google-token/rejected"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.detail").value("invalid Google credential"));

		assertThat(logs.list).isEmpty();
	}

	/**
	 * The advice never touches the request, so a credential the client sends cannot end up in the
	 * log even when the handler runs on the same request.
	 */
	@Test
	void feederNotFound_shouldNotLogTheAuthorizationHeader_whenTheRequestCarriesOne() throws Exception {
		mockMvc.perform(get("/test/feeders/missing").header("Authorization", "Bearer sentinel-credential"))
				.andExpect(status().isNotFound());

		assertSingleInfoLine("Feeder not found: feeder " + ApiExceptionHandlerStubController.FEEDER_ID + " does not exist");
		assertThat(logs.list.getFirst().getFormattedMessage()).doesNotContain("sentinel-credential");
	}
}
