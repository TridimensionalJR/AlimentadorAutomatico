package com.threedimensional.feeder.service;

import com.threedimensional.feeder.exception.DeviceAlreadyRegisteredException;
import com.threedimensional.feeder.exception.FeederNotFoundException;
import com.threedimensional.feeder.exception.InvalidFeederDataException;
import com.threedimensional.feeder.exception.UserNotFoundException;
import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.User;
import com.threedimensional.feeder.repository.FeederRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The business rules of the feeder lifecycle. Mockito only, unlike the repository-level service
 * tests: the outcomes worth proving here are the negative ones - a foreign feeder answering as
 * missing, an account deleted while its token still lives - and the flush race, which needs the
 * repository to fail on demand. What the real SQL does with these calls is covered by
 * {@code FeederRepositoryTests}; this class proves the rules around it.
 * <p>
 * Lookups are verified with the exact {@code (id, userId)} arguments: the two are both UUIDs, so
 * {@code any()} would let a swapped pair pass while every request answered 404.
 */
@ExtendWith(MockitoExtension.class)
class FeederServiceTests {

	private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID FEEDER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID DEVICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	@Mock
	private FeederRepository feederRepository;

	@Mock
	private UserService userService;

	@InjectMocks
	private FeederService feederService;

	private User user;
	private Feeder feeder;

	@BeforeEach
	void setUp() {
		user = User.create("google-abc", "tiago@example.com", "Tiago");
		feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", "Tiago's feeder", user);
	}

	// --- create -------------------------------------------------------------------------------

	@Test
	void create_shouldRegisterTheFeeder_whenTheDeviceIsFree() {
		when(userService.getById(USER_ID)).thenReturn(user);
		when(feederRepository.saveAndFlush(any(Feeder.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		Feeder created = feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", "Tiago's feeder", null);

		ArgumentCaptor<Feeder> saved = ArgumentCaptor.forClass(Feeder.class);
		verify(userService).getById(USER_ID);
		verify(feederRepository).existsByDeviceId(DEVICE_ID);
		verify(feederRepository).saveAndFlush(saved.capture());
		assertThat(created.getDeviceId()).isEqualTo(DEVICE_ID);
		assertThat(created.getName()).isEqualTo("Alimentador da sala");
		assertThat(created.getDescription()).isEqualTo("Tiago's feeder");
		assertThat(created.getIsActive()).isTrue();
		assertThat(saved.getValue().getUser()).isSameAs(user);
	}

	@Test
	void create_shouldApplyTheGivenTimezone_whenOneIsGiven() {
		when(userService.getById(USER_ID)).thenReturn(user);
		when(feederRepository.saveAndFlush(any(Feeder.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", null, "America/Manaus");

		ArgumentCaptor<Feeder> saved = ArgumentCaptor.forClass(Feeder.class);
		verify(feederRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getTimezone()).isEqualTo("America/Manaus");
	}

	@Test
	void create_shouldKeepTheDomainDefault_whenTimezoneIsNull() {
		when(userService.getById(USER_ID)).thenReturn(user);
		when(feederRepository.saveAndFlush(any(Feeder.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));

		feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", null, null);

		ArgumentCaptor<Feeder> saved = ArgumentCaptor.forClass(Feeder.class);
		verify(feederRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getTimezone()).isEqualTo("America/Bahia");
	}

	/**
	 * The account is loaded before the device is checked, so a deleted owner never gets the 409 of
	 * a duplicated device: verifyNoMoreInteractions proves existsByDeviceId was not reached.
	 */
	@Test
	void create_shouldReject_whenTheAccountNoLongerExists() {
		when(userService.getById(USER_ID)).thenThrow(new UserNotFoundException(USER_ID));

		assertThatThrownBy(() -> feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", null, null))
				.isInstanceOf(UserNotFoundException.class);

		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void create_shouldReject_whenTheDeviceIsAlreadyRegistered() {
		when(userService.getById(USER_ID)).thenReturn(user);
		when(feederRepository.existsByDeviceId(DEVICE_ID)).thenReturn(true);

		assertThatThrownBy(() -> feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", null, null))
				.isInstanceOf(DeviceAlreadyRegisteredException.class);

		verify(feederRepository).existsByDeviceId(DEVICE_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void create_shouldReject_whenTheUniqueConstraintLosesTheRace() {
		when(userService.getById(USER_ID)).thenReturn(user);
		when(feederRepository.saveAndFlush(any(Feeder.class)))
				.thenThrow(new DataIntegrityViolationException("uk_feeders_device_id"));

		assertThatThrownBy(() -> feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", null, null))
				.isInstanceOf(DeviceAlreadyRegisteredException.class);

		verify(feederRepository).existsByDeviceId(DEVICE_ID);
		verify(feederRepository).saveAndFlush(any(Feeder.class));
		verifyNoMoreInteractions(feederRepository);
	}

	/**
	 * An empty but non-null timezone passes the DTO and is rejected by the domain. The helper labels
	 * that rejection as bad input, and the domain message (which names the value) is preserved for
	 * the log, never for the response.
	 */
	@Test
	void create_shouldTranslateDomainValidation_whenTheTimezoneIsEmpty() {
		when(userService.getById(USER_ID)).thenReturn(user);

		assertThatThrownBy(() -> feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", null, ""))
				.isInstanceOf(InvalidFeederDataException.class)
				.hasMessage("timezone must not be blank")
				.hasCauseInstanceOf(IllegalArgumentException.class);

		verify(feederRepository).existsByDeviceId(DEVICE_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	/**
	 * The helper wraps the domain calls only. A repository failing with an IAE is a bug, not bad
	 * input: translating it would report a server defect as the caller's fault, so its type must
	 * survive and the flush is never reached.
	 */
	@Test
	void create_shouldNotTranslate_whenTheRepositoryThrowsIllegalArgument() {
		when(userService.getById(USER_ID)).thenReturn(user);
		when(feederRepository.existsByDeviceId(DEVICE_ID))
				.thenThrow(new IllegalArgumentException("Id must not be null"));

		assertThatThrownBy(() -> feederService.create(USER_ID, DEVICE_ID, "Alimentador da sala", null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.isNotInstanceOf(InvalidFeederDataException.class);

		verify(feederRepository).existsByDeviceId(DEVICE_ID);
		verify(feederRepository, never()).saveAndFlush(any(Feeder.class));
		verifyNoMoreInteractions(feederRepository);
	}

	// --- list ---------------------------------------------------------------------------------

	@Test
	void list_shouldReturnTheOwnersFeeders_whenThereAreAny() {
		when(feederRepository.findByUserId(USER_ID)).thenReturn(List.of(feeder));

		List<Feeder> feeders = feederService.list(USER_ID);

		assertThat(feeders).containsExactly(feeder);
		verify(feederRepository).findByUserId(USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void list_shouldReturnAnEmptyList_whenTheOwnerHasNone() {
		when(feederRepository.findByUserId(USER_ID)).thenReturn(List.of());

		List<Feeder> feeders = feederService.list(USER_ID);

		assertThat(feeders).isEmpty();
		verify(feederRepository).findByUserId(USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	// --- get ----------------------------------------------------------------------------------

	@Test
	void get_shouldReturnTheOwnersFeeder_whenItExists() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		Feeder found = feederService.get(USER_ID, FEEDER_ID);

		assertThat(found).isSameAs(feeder);
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	/**
	 * The owner filter lives in the query, so another account's feeder arrives as
	 * {@code Optional.empty()} exactly like a missing one; the SQL itself is proven by T06.
	 */
	@Test
	void get_shouldReject_whenTheFeederIsNotTheCallers() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> feederService.get(USER_ID, FEEDER_ID))
				.isInstanceOf(FeederNotFoundException.class);

		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	// --- update -------------------------------------------------------------------------------

	@Test
	void update_shouldReplaceTheLabels_whenTheFeederBelongsToTheCaller() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		Feeder updated = feederService.update(USER_ID, FEEDER_ID, "Alimentador do quarto", null);

		assertThat(updated.getName()).isEqualTo("Alimentador do quarto");
		assertThat(updated.getDescription()).isNull();
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void update_shouldReject_whenTheFeederIsNotTheCallers() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> feederService.update(USER_ID, FEEDER_ID, "Alimentador do quarto", null))
				.isInstanceOf(FeederNotFoundException.class);

		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	/** A blank name that got past the DTO is the domain's rejection, labelled as bad input. */
	@Test
	void update_shouldTranslateDomainValidation_whenTheNameIsBlank() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		assertThatThrownBy(() -> feederService.update(USER_ID, FEEDER_ID, "   ", null))
				.isInstanceOf(InvalidFeederDataException.class)
				.hasMessage("name must not be blank")
				.hasCauseInstanceOf(IllegalArgumentException.class);

		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	// --- updateTimezone -----------------------------------------------------------------------

	@Test
	void updateTimezone_shouldMoveTheFeeder_whenItBelongsToTheCaller() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		Feeder updated = feederService.updateTimezone(USER_ID, FEEDER_ID, "America/Manaus");

		assertThat(updated.getTimezone()).isEqualTo("America/Manaus");
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void updateTimezone_shouldReject_whenTheFeederIsNotTheCallers() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> feederService.updateTimezone(USER_ID, FEEDER_ID, "America/Manaus"))
				.isInstanceOf(FeederNotFoundException.class);

		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	/** A well-formed but unknown zone passes the DTO and is rejected by the domain, not by the DB. */
	@Test
	void updateTimezone_shouldTranslateDomainValidation_whenTheZoneIsUnknown() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		assertThatThrownBy(() -> feederService.updateTimezone(USER_ID, FEEDER_ID, "Mars/Olympus"))
				.isInstanceOf(InvalidFeederDataException.class)
				.hasMessageContaining("timezone must be a valid zone id")
				.hasCauseInstanceOf(IllegalArgumentException.class);

		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	// --- activate / deactivate ----------------------------------------------------------------

	@Test
	void activate_shouldSwitchTheFeederOn_whenItBelongsToTheCaller() {
		feeder.deactivate();
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		Feeder activated = feederService.activate(USER_ID, FEEDER_ID);

		assertThat(activated.getIsActive()).isTrue();
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void activate_shouldReject_whenTheFeederIsNotTheCallers() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> feederService.activate(USER_ID, FEEDER_ID))
				.isInstanceOf(FeederNotFoundException.class);

		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void deactivate_shouldSwitchTheFeederOff_whenItBelongsToTheCaller() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		Feeder deactivated = feederService.deactivate(USER_ID, FEEDER_ID);

		assertThat(deactivated.getIsActive()).isFalse();
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	/** The controller exposes a plain POST, not a toggle: repeating it must be a no-op, not a flip. */
	@Test
	void activate_shouldStayActive_whenTheFeederIsAlreadyOn() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		Feeder activated = feederService.activate(USER_ID, FEEDER_ID);

		assertThat(activated.getIsActive()).isTrue();
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void deactivate_shouldStayInactive_whenTheFeederIsAlreadyOff() {
		feeder.deactivate();
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		Feeder deactivated = feederService.deactivate(USER_ID, FEEDER_ID);

		assertThat(deactivated.getIsActive()).isFalse();
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	@Test
	void deactivate_shouldReject_whenTheFeederIsNotTheCallers() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> feederService.deactivate(USER_ID, FEEDER_ID))
				.isInstanceOf(FeederNotFoundException.class);

		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	// --- delete -------------------------------------------------------------------------------

	@Test
	void delete_shouldRemoveTheFeeder_whenItBelongsToTheCaller() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.of(feeder));

		feederService.delete(USER_ID, FEEDER_ID);

		verify(feederRepository).delete(feeder);
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}

	/**
	 * A foreign feeder is deleted by nobody: it never reaches {@code delete}, because the lookup
	 * that filters by owner already answered empty. The SQL is proven by T06.
	 */
	@Test
	void delete_shouldReject_whenTheFeederIsNotTheCallers() {
		when(feederRepository.findByIdAndUserId(FEEDER_ID, USER_ID)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> feederService.delete(USER_ID, FEEDER_ID))
				.isInstanceOf(FeederNotFoundException.class);

		verify(feederRepository, never()).delete(any(Feeder.class));
		verify(feederRepository).findByIdAndUserId(FEEDER_ID, USER_ID);
		verifyNoMoreInteractions(feederRepository);
	}
}
