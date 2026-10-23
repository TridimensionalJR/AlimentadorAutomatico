package com.threedimensional.feeder.dto;

import com.threedimensional.feeder.model.Feeder;
import com.threedimensional.feeder.model.User;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The feeder DTOs as a wire contract: the limits that mirror the columns, the fields a body may
 * not carry, and the fields a response must not leak. No Spring context is started - Bean
 * Validation works on its own, and what is under test is the records, not how MVC invokes them.
 * <p>
 * An unknown timezone is deliberately absent from this suite: {@code Feeder.updateTimezone} is
 * the one place that parses it, and the exception handler maps its rejection to 400.
 */
class FeederDtoTests {

	private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

	private static final UUID FEEDER_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
	private static final UUID DEVICE_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final Instant CREATED_AT = Instant.parse("2026-01-02T03:04:05Z");
	private static final Instant UPDATED_AT = Instant.parse("2026-02-03T04:05:06Z");
	private static final String NAME = "Alimentador da sala";
	private static final String DESCRIPTION = "Tiago's feeder";
	private static final String TOO_LONG = "x".repeat(256);
	private static final String TIMEZONE_TOO_LONG = "x".repeat(65);

	// --- Creating ---------------------------------------------------------------------------

	@Test
	void create_shouldRejectNullDeviceId_whenItIsMissing() {
		assertViolationsOn(new CreateFeederRequest(null, NAME, null, null), "deviceId");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void create_shouldRejectBlankName_whenNameIsBlank(String name) {
		assertViolationsOn(new CreateFeederRequest(DEVICE_ID, name, null, null), "name");
	}

	@Test
	void create_shouldRejectName_whenItIsLongerThanTheColumn() {
		assertViolationsOn(new CreateFeederRequest(DEVICE_ID, TOO_LONG, null, null), "name");
	}

	@Test
	void create_shouldRejectDescription_whenItIsLongerThanTheColumn() {
		assertViolationsOn(new CreateFeederRequest(DEVICE_ID, NAME, TOO_LONG, null), "description");
	}

	@Test
	void create_shouldRejectTimezone_whenItIsLongerThanTheColumn() {
		assertViolationsOn(new CreateFeederRequest(DEVICE_ID, NAME, null, TIMEZONE_TOO_LONG), "timezone");
	}

	@Test
	void create_shouldAcceptNullTimezone_whenItIsAbsent() {
		assertThat(VALIDATOR.validate(new CreateFeederRequest(DEVICE_ID, NAME, null, null))).isEmpty();
	}

	/**
	 * Pins the split of responsibilities: an empty zone is not this record's problem, it is the
	 * domain's, and the handler makes it a 400. Adding {@code @NotBlank} here would duplicate
	 * {@code Feeder.validateTimezone}.
	 */
	@Test
	void create_shouldNotReportEmptyTimezone_whenTheDomainOwnsTheCheck() {
		assertThat(VALIDATOR.validate(new CreateFeederRequest(DEVICE_ID, NAME, null, ""))).isEmpty();
	}

	/** An empty description is a value, not an absence; only length is constrained. */
	@Test
	void create_shouldAcceptEmptyDescription_whenItIsEmpty() {
		assertThat(VALIDATOR.validate(new CreateFeederRequest(DEVICE_ID, NAME, "", null))).isEmpty();
	}

	/**
	 * The other half of the ceiling: a limit tighter than the column (say 254) would pass every
	 * rejection test above and still refuse a value the database stores. The zone is only
	 * length-checked here on purpose - 64 characters that are not a zone are the domain's 400.
	 */
	@Test
	void create_shouldAcceptLengths_whenTheyExactlyMatchTheColumns() {
		assertThat(VALIDATOR.validate(new CreateFeederRequest(
				DEVICE_ID, "x".repeat(255), "x".repeat(255), "x".repeat(64)))).isEmpty();
	}

	// --- Updating ---------------------------------------------------------------------------

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void update_shouldRejectBlankName_whenNameIsBlank(String name) {
		assertViolationsOn(new UpdateFeederRequest(name, null), "name");
	}

	@Test
	void update_shouldRejectName_whenItIsLongerThanTheColumn() {
		assertViolationsOn(new UpdateFeederRequest(TOO_LONG, null), "name");
	}

	@Test
	void update_shouldRejectDescription_whenItIsLongerThanTheColumn() {
		assertViolationsOn(new UpdateFeederRequest(NAME, TOO_LONG), "description");
	}

	/** Same rule as on creation: clearing the description is legitimate. */
	@Test
	void update_shouldAcceptEmptyDescription_whenItIsEmpty() {
		assertThat(VALIDATOR.validate(new UpdateFeederRequest(NAME, ""))).isEmpty();
	}

	@Test
	void update_shouldAcceptLengths_whenTheyExactlyMatchTheColumns() {
		assertThat(VALIDATOR.validate(new UpdateFeederRequest("x".repeat(255), "x".repeat(255)))).isEmpty();
	}

	// --- Changing the zone ------------------------------------------------------------------

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void updateTimezone_shouldRejectBlankTimezone_whenTimezoneIsBlank(String timezone) {
		assertViolationsOn(new UpdateFeederTimezoneRequest(timezone), "timezone");
	}

	@Test
	void updateTimezone_shouldRejectTimezone_whenItIsLongerThanTheColumn() {
		assertViolationsOn(new UpdateFeederTimezoneRequest(TIMEZONE_TOO_LONG), "timezone");
	}

	@Test
	void updateTimezone_shouldAcceptTimezone_whenItExactlyMatchesTheColumn() {
		assertThat(VALIDATOR.validate(new UpdateFeederTimezoneRequest("x".repeat(64)))).isEmpty();
	}

	// --- Wire shape -------------------------------------------------------------------------

	/**
	 * Mass assignment needs a component for the value to land in, because Jackson drops body
	 * fields that match nothing. Pinning the whole component list is stronger than listing the
	 * forbidden names: an {@code ownerId} invented later would slip past a blocklist.
	 */
	@Test
	void create_shouldCarryNoServerOwnedComponent_whenInspectedStructurally() {
		assertThat(componentNames(CreateFeederRequest.class))
				.containsExactly("deviceId", "name", "description", "timezone");
	}

	/** {@code deviceId} is absent too: the pairing is immutable and has no edit route. */
	@Test
	void update_shouldCarryNoImmutableComponent_whenInspectedStructurally() {
		assertThat(componentNames(UpdateFeederRequest.class)).containsExactly("name", "description");
	}

	@Test
	void updateTimezone_shouldCarryNoImmutableComponent_whenInspectedStructurally() {
		assertThat(componentNames(UpdateFeederTimezoneRequest.class)).containsExactly("timezone");
	}

	// --- Responding -------------------------------------------------------------------------

	/**
	 * Renamed and moved on purpose: a response that hard-coded the construction values or a
	 * default zone would pass a test that only read them once.
	 */
	@Test
	void from_shouldMapEveryField_whenTheFeederIsPopulated() {
		Feeder feeder = feeder();
		feeder.update("Alimentador do quarto", "Tiago's bedroom feeder");
		feeder.updateTimezone("America/Manaus");

		FeederResponse response = FeederResponse.from(feeder);

		assertThat(response.id()).isEqualTo(FEEDER_ID);
		assertThat(response.deviceId()).isEqualTo(DEVICE_ID);
		assertThat(response.name()).isEqualTo("Alimentador do quarto");
		assertThat(response.description()).isEqualTo("Tiago's bedroom feeder");
		assertThat(response.timezone()).isEqualTo("America/Manaus");
		assertThat(response.active()).isTrue();
		assertThat(response.createdAt()).isEqualTo(CREATED_AT);
		assertThat(response.updatedAt()).isEqualTo(UPDATED_AT);
	}

	@Test
	void from_shouldReportInactive_whenTheFeederIsDeactivated() {
		Feeder feeder = feeder();
		feeder.deactivate();

		assertThat(FeederResponse.from(feeder).active()).isFalse();
	}

	@Test
	void from_shouldKeepNulls_whenDescriptionAndUpdatedAtAreAbsent() {
		FeederResponse response = FeederResponse.from(
				Feeder.create(DEVICE_ID, NAME, null, owner()));

		assertThat(response.description()).isNull();
		assertThat(response.updatedAt()).isNull();
		assertThat(response.active()).isTrue();
	}

	/**
	 * Structural proof that no owner data can leave through this response: the whole component
	 * list is pinned, so a carrying field cannot be added under any name - a blocklist would miss
	 * an {@code owner} or {@code accountId}. The JSON-level check belongs to the controller test,
	 * which sees the real serialized body.
	 */
	@Test
	void from_shouldNotExposeOwnerData() {
		assertThat(componentNames(FeederResponse.class))
				.containsExactly("id", "deviceId", "name", "description", "timezone", "active",
						"createdAt", "updatedAt");
	}

	/**
	 * The association is lazy and, once the transaction is over, unreadable: a read here would
	 * surface as {@code LazyInitializationException} on every response. A real entity cannot show
	 * the difference (the owner is still in memory), so the mock records the call.
	 */
	@Test
	void from_shouldNotReadTheOwner_whenBuildingTheResponse() {
		Feeder feeder = mock(Feeder.class);

		FeederResponse.from(feeder);

		verify(feeder, never()).getUser();
	}

	// --- Helpers ----------------------------------------------------------------------------

	/** Only the reported fields are asserted, so a constraint added by mistake shows up here. */
	private static void assertViolationsOn(Object request, String... fields) {
		assertThat(VALIDATOR.validate(request).stream()
				.map(violation -> violation.getPropertyPath().toString())
				.toList())
				.containsExactlyInAnyOrder(fields);
	}

	private static List<String> componentNames(Class<?> type) {
		return Arrays.stream(type.getRecordComponents())
				.map(RecordComponent::getName)
				.toList();
	}

	private static User owner() {
		return User.create("google-abc", "tiago@example.com", "Tiago");
	}

	/**
	 * Feeder has no setters and no id or audit instants until persisted, so the three values JPA
	 * would provide are placed by hand - the same trick {@code UserControllerTests} uses. Active
	 * state needs no reflection: creation starts it on and {@code deactivate()} switches it off.
	 */
	private static Feeder feeder() {
		Feeder feeder = Feeder.create(DEVICE_ID, NAME, DESCRIPTION, owner());
		ReflectionTestUtils.setField(feeder, "id", FEEDER_ID);
		ReflectionTestUtils.setField(feeder, "createdAt", CREATED_AT);
		ReflectionTestUtils.setField(feeder, "updatedAt", UPDATED_AT);
		return feeder;
	}
}
