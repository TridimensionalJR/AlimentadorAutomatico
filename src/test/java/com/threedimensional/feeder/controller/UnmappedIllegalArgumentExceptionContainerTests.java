package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The half of the T07 contract that a {@code MockMvc} slice cannot see: what a real client gets when
 * an {@link IllegalArgumentException} escapes a controller without being labelled as bad input.
 * <p>
 * {@code ApiExceptionHandlerTests} proves the exception is no longer claimed by the advice, but
 * {@code MockMvc} stops at the {@code ServletException} instead of running the ERROR dispatch. Only a
 * servlet container answers the status a client actually reads, so this test starts one on a random
 * port and asks the stub route with a real token. It also pins the other half of the contract: the
 * 500 must not echo the exception message.
 * <p>
 * One request, no threads and no rows touched: the stub throws before reaching any collaborator,
 * which is why this stays a cheap, deterministic test instead of a second suite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class UnmappedIllegalArgumentExceptionContainerTests {

	private static final UUID USER_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private JwtService jwtService;

	/**
	 * The status is the assertion that matters: 500 is what makes the bug visible, and any handler
	 * claiming IAE again would turn this into a 400. The message is the domain-internal text the
	 * response must never carry.
	 */
	@Test
	void unmappedIllegalArgument_shouldAnswer500_whenAControllerThrowsIt() throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/test/illegal-argument"))
				.header("Authorization", "Bearer " + jwtService.issue(user()).value())
				.GET()
				.build();

		HttpResponse<String> response =
				HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.body()).doesNotContain("Id must not be null");
	}

	/** The token only needs a subject; the stub throws before anything reads the account. */
	private static User user() {
		User user = User.create("google-abc", "tiago@example.com", "Tiago");
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}
}
