package com.threedimensional.feeder.model;

import com.threedimensional.feeder.model.enums.ConfigType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Domain rules for {@link FeederConfig}, with no Spring context and no database. Everything here is
 * plain Java, so booting the context would only add seconds: the factory methods, the update rules
 * and the boundary checks are all decided by the arguments passed in.
 * <p>
 * What this suite cannot prove is that the database mirrors these same rules. That is
 * {@code FeederConfigRepositoryTests}' job, and the two are deliberately overlapping: a rule that
 * holds in the domain but not in the CHECK constraint is a row that can be corrupted by any future
 * native query or console session.
 */
class FeederConfigTests {

	private static final UUID DEVICE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	/** 2026-09-28 is a Monday and 2026-09-29 a Tuesday, which is what the weekday assertions rely on. */
	private static final LocalDate MONDAY = LocalDate.of(2026, 9, 28);
	private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 29);

	private Feeder feeder;

	@BeforeEach
	void setUp() {
		User user = User.create("google-abc", "tiago@example.com", "Tiago");
		feeder = Feeder.create(DEVICE_ID, "Alimentador da sala", "Tiago's feeder", user);
	}

	// --- Construction -----------------------------------------------------------------------

	@Test
	void createInterval_shouldSetIntervalAndClearTimeOfDay() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		assertThat(config.getType()).isEqualTo(ConfigType.INTERVAL);
		assertThat(config.getIntervalMinutes()).isEqualTo(30);
		assertThat(config.getTimeOfDay()).isNull();
	}

	/**
	 * The forced all-week set is what makes an interval rule behave identically on every weekday.
	 * It also means the weekdays column is never consulted for INTERVAL rows.
	 */
	@Test
	void createInterval_shouldForceEveryWeekday() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		assertThat(config.getWeekdays()).containsExactlyInAnyOrder(DayOfWeek.values());
	}

	@Test
	void createDailyTime_shouldSetTimeAndClearIntervalMinutes() {
		FeederConfig config = FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 2);

		assertThat(config.getType()).isEqualTo(ConfigType.DAILY_TIME);
		assertThat(config.getTimeOfDay()).isEqualTo(LocalTime.of(8, 0));
		assertThat(config.getIntervalMinutes()).isNull();
	}

	@Test
	void create_shouldRejectNullFeeder() {
		assertThatThrownBy(() -> FeederConfig.createInterval(null, 30, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("feeder must not be null");
	}

	/**
	 * The floor and ceiling are the point of the test, not the middle of the range: 1 is the fastest
	 * interval the scheduler can advance to, and 10080 is exactly one week.
	 */
	@ParameterizedTest
	@ValueSource(ints = {1, 30, 10_080})
	void createInterval_shouldAcceptIntervalWithinBounds(int intervalMinutes) {
		assertThat(FeederConfig.createInterval(feeder, intervalMinutes, 1).getIntervalMinutes())
				.isEqualTo(intervalMinutes);
	}

	@ParameterizedTest
	@ValueSource(ints = {0, -1, 10_081})
	void createInterval_shouldRejectIntervalOutsideBounds(int intervalMinutes) {
		assertThatThrownBy(() -> FeederConfig.createInterval(feeder, intervalMinutes, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageStartingWith("interval minutes must be between");
	}

	@ParameterizedTest
	@ValueSource(ints = {0, 101})
	void create_shouldRejectDoseOutsideBounds(int dose) {
		assertThatThrownBy(() -> FeederConfig.createInterval(feeder, 30, dose))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("dose must be between 1 and 100");
	}

	/**
	 * The dose ceiling is pinned for the interval factory above; this pins it for the daily-time
	 * factory too. Both run through the same validator, so this is redundancy on purpose: a future
	 * edit to one factory must not be allowed to slip past just because the other one is tested.
	 */
	@Test
	void createDailyTime_shouldRejectDoseOutsideBounds() {
		assertThatThrownBy(() -> FeederConfig.createDailyTime(
				feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 101))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("dose must be between 1 and 100");
	}

	@Test
	void createDailyTime_shouldRejectEmptyWeekdays() {
		assertThatThrownBy(() -> FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.noneOf(DayOfWeek.class), 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("weekdays must not be empty");
	}

	@Test
	void createDailyTime_shouldRejectNullWeekdays() {
		assertThatThrownBy(() -> FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), null, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("weekdays must not be empty");
	}

	// --- Update -----------------------------------------------------------------------------

	/**
	 * Switching shapes is the only way intervalMinutes could be left set alongside timeOfDay, which
	 * is exactly the state ck_feeder_configs_spec rejects. Re-running the same conditionals that
	 * guard the constructor is what keeps the entity from ever producing it.
	 */
	@Test
	void update_shouldClearIntervalMinutes_whenSwitchingToDailyTime() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		config.update(ConfigType.DAILY_TIME, null, LocalTime.of(18, 30), EnumSet.of(DayOfWeek.TUESDAY), 1);

		assertThat(config.getIntervalMinutes()).isNull();
		assertThat(config.getTimeOfDay()).isEqualTo(LocalTime.of(18, 30));
	}

	@Test
	void update_shouldClearTimeOfDayAndForceWeekdays_whenSwitchingToInterval() {
		FeederConfig config = FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1);

		config.update(ConfigType.INTERVAL, 60, null, EnumSet.of(DayOfWeek.MONDAY), 1);

		assertThat(config.getTimeOfDay()).isNull();
		assertThat(config.getIntervalMinutes()).isEqualTo(60);
		assertThat(config.getWeekdays()).containsExactlyInAnyOrder(DayOfWeek.values());
	}

	/**
	 * update() takes a weekdays argument even when the target type is INTERVAL and will overwrite
	 * it. This pins that: a caller passing a partial set must not end up with a partial interval rule.
	 */
	@Test
	void update_shouldDiscardWeekdays_whenTypeIsInterval() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		config.update(ConfigType.INTERVAL, 30, null, EnumSet.of(DayOfWeek.MONDAY), 1);

		assertThat(config.getWeekdays()).containsExactlyInAnyOrder(DayOfWeek.values());
	}

	/**
	 * The set is copied into an unmodifiable EnumSet on the way in. Without that copy a caller
	 * holding the original reference could widen its own rule after validation, bypassing every
	 * check that just ran.
	 */
	@Test
	void weekdays_shouldNotChange_whenCallerMutatesTheSetItPassedIn() {
		EnumSet<DayOfWeek> weekdays = EnumSet.of(DayOfWeek.MONDAY);
		FeederConfig config = FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), weekdays, 1);

		weekdays.add(DayOfWeek.SATURDAY);

		assertThat(config.getWeekdays()).containsExactly(DayOfWeek.MONDAY);
	}

	/**
	 * update() re-runs the same validators as the constructor, so every rule the factories reject
	 * has to be rejected when editing an existing rule too. These three mirror the factory-side
	 * assertions: an edit is just as able to pass a 0, a missing type, or a missing time.
	 */
	@Test
	void update_shouldRejectDoseOutsideBounds() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		assertThatThrownBy(() -> config.update(ConfigType.INTERVAL, 30, null, null, 0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("dose must be between 1 and 100");
	}

	@Test
	void update_shouldRejectNullType() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		assertThatThrownBy(() -> config.update(null, 30, null, null, 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("type must not be null");
	}

	@Test
	void update_shouldRejectNullTimeOfDay_whenTargetTypeIsDaily() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		assertThatThrownBy(() -> config.update(ConfigType.DAILY_TIME, null, null, EnumSet.of(DayOfWeek.MONDAY), 1))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("time of day must not be null");
	}

	// --- Runtime state ----------------------------------------------------------------------

	@Test
	void registerRun_shouldRecordTheInstantItWasGiven() {
		Instant ranAt = Instant.parse("2026-09-28T11:00:00Z");
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		config.registerRun(ranAt);

		assertThat(config.getLastRunAt()).isEqualTo(ranAt);
	}

	@Test
	void registerRun_shouldRejectNull() {
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);

		assertThatThrownBy(() -> config.registerRun(null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("run moment must not be null");
	}

	@Test
	void isDueOn_shouldReturnTrue_whenActiveAndWeekdaySelected() {
		FeederConfig config = FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1);

		assertThat(config.isDueOn(MONDAY)).isTrue();
	}

	@Test
	void isDueOn_shouldReturnFalse_whenWeekdayNotSelected() {
		FeederConfig config = FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1);

		assertThat(config.isDueOn(TUESDAY)).isFalse();
	}

	@Test
	void isDueOn_shouldReturnFalse_whenInactive() {
		FeederConfig config = FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1);
		config.deactivate();

		assertThat(config.isDueOn(MONDAY)).isFalse();
	}

	@Test
	void activate_shouldMakeAnInactiveRuleDueAgain() {
		FeederConfig config = FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1);
		config.deactivate();

		config.activate();

		assertThat(config.isDueOn(MONDAY)).isTrue();
	}
}
