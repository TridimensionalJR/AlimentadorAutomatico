package com.threedimensional.feeder.controller;

import com.threedimensional.feeder.config.JwtConfig;
import com.threedimensional.feeder.config.SecurityConfig;
import com.threedimensional.feeder.exception.UserHasFeedersException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.service.JwtService;
import com.threedimensional.feeder.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.mapping.JpaMetamodelMappingContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The signed-in user's own account, reading it and deleting it, through the real filter chain.
 * Tokens come from the real {@link JwtService}, so what is exercised is the whole path a client
 * takes: a token this application issues, accepted by the filter, its subject turned into the
 * account that is loaded or deleted. The service is mocked, since its own behavior is covered in
 * {@code UserServiceTests}.
 * <p>
 * Same slice setup as {@code AuthControllerTests}: the security and token beans are imported
 * explicitly because a web slice scans neither {@code @Configuration} nor {@code @Service} classes.
 */
@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtConfig.class, JwtService.class})
class UserControllerTests {

	private static final UUID USER_ID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JwtService jwtService;

	@MockitoBean
	private UserService userService;

	/** Same reason as in {@code AuthControllerTests}: the application class enables JPA auditing. */
	@MockitoBean
	private JpaMetamodelMappingContext jpaMetamodelMappingContext;

	/**
	 * Besides the body, the lookup itself is asserted: the account loaded is the one named in the
	 * token subject. The request carries nothing else that could name an account, which is the point.
	 */
	@Test
	void me_shouldReturnTheProfileOfTheTokenOwner() throws Exception {
		when(userService.getById(USER_ID)).thenReturn(user());

		mockMvc.perform(get("/me").header("Authorization", bearer()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(USER_ID.toString()))
				.andExpect(jsonPath("$.email").value("tiago@example.com"))
				.andExpect(jsonPath("$.name").value("Tiago"));

		verify(userService).getById(USER_ID);
		verifyNoMoreInteractions(userService);
	}

	/** Same guarantee as sign-in: the Google subject never leaves the backend. */
	@Test
	void me_shouldNotExposeTheGoogleSubject() throws Exception {
		when(userService.getById(USER_ID)).thenReturn(user());

		mockMvc.perform(get("/me").header("Authorization", bearer()))
				.andExpect(jsonPath("$.googleId").doesNotExist());
	}

	@Test
	void me_shouldReturn401_whenThereIsNoToken() throws Exception {
		mockMvc.perform(get("/me"))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(userService);
	}

	/**
	 * A token outlives the account it was issued for. The 404 body names what is missing and nothing
	 * more: in particular the id carried by the exception message stays out of it.
	 */
	@Test
	void me_shouldReturn404_whenTheAccountNoLongerExists() throws Exception {
		when(userService.getById(USER_ID)).thenThrow(new UserNotFoundException(USER_ID));

		mockMvc.perform(get("/me").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("user not found"));
	}

	// --- Deleting the account -----------------------------------------------------------------

	/**
	 * What is deleted is the account named in the token subject, asserted on the call the service
	 * receives. Nothing in the request could name another one.
	 */
	@Test
	void deleteMe_shouldReturn204_andDeleteTheAccountOfTheTokenOwner() throws Exception {
		mockMvc.perform(delete("/me").header("Authorization", bearer()))
				.andExpect(status().isNoContent());

		verify(userService).delete(USER_ID);
		verifyNoMoreInteractions(userService);
	}

	@Test
	void deleteMe_shouldReturn401_whenThereIsNoToken() throws Exception {
		mockMvc.perform(delete("/me"))
				.andExpect(status().isUnauthorized());

		verifyNoInteractions(userService);
	}

	/**
	 * There is no route that takes an id, so deleting someone else's account is not a request this API
	 * can receive at all. Even with a valid token, the call never reaches the service.
	 */
	@Test
	void deleteMe_shouldNotExist_withAnIdInTheRoute() throws Exception {
		mockMvc.perform(delete("/me/ffffffff-ffff-ffff-ffff-ffffffffffff").header("Authorization", bearer()))
				.andExpect(status().isNotFound());

		verifyNoInteractions(userService);
	}

	/** Deleting twice, or with a token that outlived its account, ends in the same 404 as {@code GET /me}. */
	@Test
	void deleteMe_shouldReturn404_whenTheAccountNoLongerExists() throws Exception {
		doThrow(new UserNotFoundException(USER_ID)).when(userService).delete(USER_ID);

		mockMvc.perform(delete("/me").header("Authorization", bearer()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.detail").value("user not found"));
	}

	/** The caller is told what to do next, and the id in the exception message is not echoed back. */
	@Test
	void deleteMe_shouldReturn409_whenTheAccountStillOwnsFeeders() throws Exception {
		doThrow(new UserHasFeedersException(USER_ID)).when(userService).delete(USER_ID);

		mockMvc.perform(delete("/me").header("Authorization", bearer()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("the account still has feeders: remove them before deleting it"));
	}

	// --- Helpers ------------------------------------------------------------------------------

	private String bearer() {
		return "Bearer " + jwtService.issue(user()).value();
	}

	/** User has no setters and no id until persisted, so the id is placed by hand. */
	private static User user() {
		User user = User.create("google-abc", "tiago@example.com", "Tiago");
		ReflectionTestUtils.setField(user, "id", USER_ID);
		return user;
	}
}
