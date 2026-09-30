package com.threedimensional.feeder.repository;

import com.threedimensional.feeder.model.DayOfWeekSetConverter;
import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.FeederConfig;
import com.threedimensional.feeder.model.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves that the database enforces the same rules as the domain, which
 * {@code FeederConfigTests} can only assume.
 * <p>
 * The two kinds of test here are deliberately different. The UNIQUE cases are reachable through
 * normal code, so they go through the domain and assert the save fails. The CHECK cases are not
 * reachable that way, because the entity refuses to build the offending object in the first place.
 * They therefore have to be inserted as raw SQL, which is the only way to answer the question that
 * actually matters: if a future migration, a native query or a psql session produces a bad row,
 * does the database stop it?
 * <p>
 * Each test runs in a transaction that is rolled back, so none of this leaks between tests.
 */
@DataJpaTest
class FeederConfigRepositoryTests {

	/** SQL-standard integrity constraint violation, the same on H2 and PostgreSQL. */
	private static final String CHECK_VIOLATION = "23513";

	@Autowired
	private FeederConfigRepository configRepository;

	@Autowired
	private FeederRepository feederRepository;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private EntityManager entityManager;

	private Feeder feeder;

	@BeforeEach
	void setUp() {
		User user = userRepository.saveAndFlush(User.create("google-abc", "tiago@example.com", "Tiago"));
		feeder = feederRepository.saveAndFlush(Feeder.create(UUID.fromString("33333333-3333-3333-3333-333333333333"),
				"Alimentador da sala", "Tiago's feeder", user));
	}

	// --- UNIQUE constraints, reached through the domain -------------------------------------

	/**
	 * Weekdays are deliberately absent from uk_feeder_configs_time. Two rules at 08:00 on Monday and
	 * Tuesday would double-dispense on any day they overlapped, so the database rejects the second
	 * one even though the domain considers them different rules.
	 */
	@Test
	void save_shouldRejectSecondTimedRule_whenSameFeederAndSameTime() {
		configRepository.saveAndFlush(
				FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1));

		assertThatThrownBy(() -> configRepository.saveAndFlush(
				FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.TUESDAY), 1)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void save_shouldRejectSecondIntervalRule_whenSameFeederAndSameMinutes() {
		configRepository.saveAndFlush(FeederConfig.createInterval(feeder, 30, 1));

		assertThatThrownBy(() -> configRepository.saveAndFlush(FeederConfig.createInterval(feeder, 30, 2)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/**
	 * The same time on a different feeder is not a collision, which is what makes one UNIQUE per
	 * shape sufficient instead of a constraint on the whole schedule.
	 */
	@Test
	void save_shouldAcceptSameTimeOnAnotherFeeder() {
		User other = userRepository.saveAndFlush(User.create("google-xyz", "bruna@example.com", "Bruna"));
		Feeder otherFeeder = feederRepository.saveAndFlush(
				Feeder.create(UUID.fromString("44444444-4444-4444-4444-444444444444"), "Alimentador do escritorio", null, other));
		configRepository.saveAndFlush(
				FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1));

		FeederConfig onOther = FeederConfig.createDailyTime(otherFeeder, LocalTime.of(8, 0), EnumSet.of(DayOfWeek.MONDAY), 1);

		assertThat(configRepository.saveAndFlush(onOther).getId()).isNotNull();
	}

	// --- CHECK constraints, only reachable with raw SQL ---------------------------------------

	@Test
	void insert_shouldRejectZeroInterval_whenDomainValidationIsBypassed() {
		assertSqlState(CHECK_VIOLATION, "CK_FEEDER_CONFIGS_INTERVAL_MINUTES", """
				insert into feeder_configs (id, type, interval_minutes, weekdays, dose, feeder_id)
				values ('55555555-5555-5555-5555-555555555555', 'INTERVAL', 0,
				        'MON,TUE,WED,THU,FRI,SAT,SUN', 1, '%s')
				""".formatted(feeder.getId()));
	}

	@Test
	void insert_shouldRejectDoseAboveCeiling_whenDomainValidationIsBypassed() {
		assertSqlState(CHECK_VIOLATION, "CK_FEEDER_CONFIGS_DOSE", """
				insert into feeder_configs (id, type, interval_minutes, weekdays, dose, feeder_id)
				values ('66666666-6666-6666-6666-666666666666', 'INTERVAL', 30,
				        'MON,TUE,WED,THU,FRI,SAT,SUN', 101, '%s')
				""".formatted(feeder.getId()));
	}

	/**
	 * The single most important row-level rule in the schema: an INTERVAL rule that also carries a
	 * time of day. It would pass every per-column check, and it is the corruption that would break
	 * the two UNIQUE constraints. Only ck_feeder_configs_spec catches it.
	 */
	@Test
	void insert_shouldRejectIntervalRuleThatAlsoCarriesATimeOfDay() {
		assertSqlState(CHECK_VIOLATION, "CK_FEEDER_CONFIGS_SPEC", """
				insert into feeder_configs (id, type, interval_minutes, time_of_day, weekdays, dose, feeder_id)
				values ('77777777-7777-7777-7777-777777777777', 'INTERVAL', 30, '08:00:00',
				        'MON,TUE,WED,THU,FRI,SAT,SUN', 1, '%s')
				""".formatted(feeder.getId()));
	}

	@Test
	void insert_shouldRejectDailyTimeRuleWithoutATimeOfDay() {
		assertSqlState(CHECK_VIOLATION, "CK_FEEDER_CONFIGS_SPEC", """
				insert into feeder_configs (id, type, weekdays, dose, feeder_id)
				values ('88888888-8888-8888-8888-888888888888', 'DAILY_TIME',
				        'MON,TUE,WED,THU,FRI,SAT,SUN', 1, '%s')
				""".formatted(feeder.getId()));
	}

	@Test
	void insert_shouldRejectBlankWeekdays() {
		assertSqlState(CHECK_VIOLATION, "CK_FEEDER_CONFIGS_WEEKDAYS", """
				insert into feeder_configs (id, type, interval_minutes, weekdays, dose, feeder_id)
				values ('99999999-9999-9999-9999-999999999999', 'INTERVAL', 30, '', 1, '%s')
				""".formatted(feeder.getId()));
	}

	/**
	 * A raw query bypasses the Spring exception translator, so the failure arrives as Hibernate's
	 * ConstraintViolationException rather than a DataIntegrityViolationException. Asserting on the
	 * SQLState instead of the exception class is both shorter and stronger: 23513 and 23505 are
	 * SQL-standard codes, identical on H2 and PostgreSQL, so this assertion survives the move to
	 * production. Naming the specific CHECK also fails if the constraint is ever renamed or dropped.
	 */
	private void assertSqlState(String expectedSqlState, String expectedConstraintName, String sql) {
		assertThatThrownBy(() -> entityManager.createNativeQuery(sql).executeUpdate())
				.hasRootCauseInstanceOf(SQLException.class)
				.satisfies(thrown -> {
					SQLException cause = (SQLException) thrown.getCause();
					assertThat(cause.getSQLState()).isEqualTo(expectedSqlState);
					assertThat(cause.getMessage()).contains(expectedConstraintName);
				});
	}

	// --- Mapping round trips -------------------------------------------------------------------

	/**
	 * The converter is applied by Hibernate on the way out, so a mapping error shows up as a
	 * corrupted set rather than an exception. entityManager.clear() is what forces the read to come
	 * from the column instead of from the persistence context.
	 */
	@Test
	void findByFeederId_shouldReturnTheSameWeekdays_afterReloadingFromTheColumn() {
		EnumSet<DayOfWeek> weekdays = EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY);
		configRepository.saveAndFlush(FeederConfig.createDailyTime(feeder, LocalTime.of(8, 0), weekdays, 2));
		entityManager.clear();

		assertThat(configRepository.findByFeederId(feeder.getId()))
				.singleElement()
				.satisfies(config -> {
					assertThat(config.getWeekdays()).containsExactlyInAnyOrderElementsOf(weekdays);
					assertThat(config.getTimeOfDay()).isEqualTo(LocalTime.of(8, 0));
				});
	}

	/**
	 * timestamptz must survive the round trip without a zone shift. Brazil no longer observes
	 * daylight saving, but America/Sao_Paulo did until 2019, and a feeder configured to any other
	 * zone still would - so an Instant that comes back as the same wall-clock time but a different
	 * instant is a real, silent corruption.
	 */
	@Test
	void findByFeederId_shouldPreserveTheInstantExactly_afterReloading() {
		Instant ranAt = Instant.parse("2026-01-15T03:30:00Z");
		FeederConfig config = FeederConfig.createInterval(feeder, 30, 1);
		config.registerRun(ranAt);
		configRepository.saveAndFlush(config);
		entityManager.clear();

		assertThat(configRepository.findByFeederId(feeder.getId()))
				.singleElement()
				.extracting(FeederConfig::getLastRunAt)
				.isEqualTo(ranAt);
	}

	@Test
	void findByFeederIdAndIsActiveTrue_shouldSkipDeactivatedRules() {
		FeederConfig active = FeederConfig.createInterval(feeder, 30, 1);
		FeederConfig paused = FeederConfig.createInterval(feeder, 60, 1);
		paused.deactivate();
		configRepository.saveAllAndFlush(java.util.List.of(active, paused));
		entityManager.clear();

		assertThat(configRepository.findByFeederIdAndIsActiveTrue(feeder.getId()))
				.singleElement()
				.extracting(FeederConfig::getId)
				.isEqualTo(active.getId());
	}

	/**
	 * The zone moved to feeders in V3 with a NOT NULL default, so every row that existed before
	 * scheduling was introduced has to come back usable. This is the assertion that would have caught
	 * a nullable zone or a missing backfill.
	 */
	@Test
	void feeder_shouldExposeTheDefaultZone_afterLoadingAPersistedFeeder() {
		entityManager.clear();
		Feeder reloaded = feederRepository.findById(feeder.getId()).orElseThrow();

		assertThat(reloaded.getTimezone()).isEqualTo("America/Bahia");
		assertThat(reloaded.zoneId().getId()).isEqualTo("America/Bahia");
	}
}
