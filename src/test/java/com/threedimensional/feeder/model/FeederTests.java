package com.threedimensional.feeder.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain rules for {@link Feeder}, with no Spring context and no database, mirroring
 * {@code FeederConfigTests}. The anatomy kept {@code FeederConfigTests} out of this suite's
 * scope, but {@code updateTimezone} is the one method with real logic of its own - it validates
 * against the IANA database rather than a simple range - and the construction rules are the
 * ones every fixture of every other test builds on.
 */
class FeederTests {

	private static final UUID DEVICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

	private User user;

	@BeforeEach
	void setUp() {
		user = User.create("google-abc", "tiago@example.com", "Tiago");
	}

	// --- Construction -----------------------------------------------------------------------

	@Test
	void create_shouldSetTimezoneAndActiveByDefault() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", "Tiago's feeder", user);

		assertThat(feeder.getDeviceId()).isEqualTo(DEVICE_ID);
		assertThat(feeder.getName()).isEqualTo("Alimentador da sala");
		assertThat(feeder.getDescription()).isEqualTo("Tiago's feeder");
		assertThat(feeder.getUser()).isEqualTo(user);
		assertThat(feeder.getTimezone()).isEqualTo("America/Bahia");
		assertThat(feeder.getIsActive()).isTrue();
	}

	@Test
	void create_shouldAcceptNullDescription() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);

		assertThat(feeder.getDescription()).isNull();
	}

	@Test
	void create_shouldRejectNullDeviceId() {
		assertThatThrownBy(() -> Feeder.create(null, "Alimentador da sala", null, user))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("device id must not be null");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void create_shouldRejectBlankName(String name) {
		assertThatThrownBy(() -> Feeder.create(DEVICE_ID, name, null, user))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("name must not be blank");
	}

	@Test
	void create_shouldRejectNullUser() {
		assertThatThrownBy(() -> Feeder.create(DEVICE_ID, "Alimentador da sala", null, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("user must not be null");
	}

	// --- Update -----------------------------------------------------------------------------

	@Test
	void update_shouldRenameTheFeeder() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", "Tiago's feeder", user);

		feeder.update("Alimentador do quarto", "Tiago's bedroom feeder");

		assertThat(feeder.getName()).isEqualTo("Alimentador do quarto");
		assertThat(feeder.getDescription()).isEqualTo("Tiago's bedroom feeder");
	}

	@Test
	void update_shouldClearDescription_whenNoneIsGiven() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", "Tiago's feeder", user);

		feeder.update("Alimentador da sala", null);

		assertThat(feeder.getDescription()).isNull();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void update_shouldRejectBlankName(String name) {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);

		assertThatThrownBy(() -> feeder.update(name, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("name must not be blank");
	}

	// --- Timezone ---------------------------------------------------------------------------

	/**
	 * The default zone is a concrete IANA id, so it must parse back to itself rather than merely
	 * round-tripping a string.
	 */
	@Test
	void zoneId_shouldParseTheDefaultZone() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);

		assertThat(feeder.zoneId().getId()).isEqualTo("America/Bahia");
	}

	@Test
	void updateTimezone_shouldMoveTheFeederToAnotherZone() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);

		feeder.updateTimezone("America/Manaus");

		assertThat(feeder.getTimezone()).isEqualTo("America/Manaus");
		assertThat(feeder.zoneId().getId()).isEqualTo("America/Manaus");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void updateTimezone_shouldRejectBlankZone(String timezone) {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);

		assertThatThrownBy(() -> feeder.updateTimezone(timezone))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("timezone must not be blank");
	}

	/**
	 * A typo is rejected at write time, not at scheduling time, so a wrong zone can never silently
	 * resolve into the JVM default zone when a rule is computed later.
	 */
	@Test
	void updateTimezone_shouldRejectAnUnknownZoneId() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);

		assertThatThrownBy(() -> feeder.updateTimezone("Mars/Olympus"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("timezone must be a valid zone id: Mars/Olympus");
	}

	// --- Runtime state ----------------------------------------------------------------------

	@Test
	void deactivate_shouldSwitchTheFeederOff() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);

		feeder.deactivate();

		assertThat(feeder.getIsActive()).isFalse();
	}

	@Test
	void activate_shouldSwitchTheFeederBackOn() {
		Feeder feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", null, user);
		feeder.deactivate();

		feeder.activate();

		assertThat(feeder.getIsActive()).isTrue();
	}
}