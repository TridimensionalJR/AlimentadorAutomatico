package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@link ApiExceptionHandler} alone: each mapped exception answers the status
 * and the fixed detail from the design, and nothing the exception message carries leaks into the
 * body. The stub controller only raises them, so no real controller or service is involved. One
 * test checks the messages themselves, because the ids are supposed to live there and nowhere else.
 * <p>
 * Filters are off because authentication is not what is under test, and the advice is picked up by
 * the slice itself (a {@code @ControllerAdvice} is part of the scanned web components), so
 * importing it would register it twice.
 */
@WebMvcTest(controllers = ApiExceptionHandlerStubController.class)
@AutoConfigureMockMvc(addFilters = false)
class ApiExceptionHandlerTests {

	@Autowired
	private MockMvc mockMvc;

	/** Same reason as in {@code AuthControllerTests}: the application class enables JPA auditing. */
	@MockitoBean
	private JpaMetamodelMappingContext jpaMetamodelMappingContext;

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
}
