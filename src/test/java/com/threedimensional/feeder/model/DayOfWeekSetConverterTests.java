package com.threedimensional.feeder.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The converter is the only place where a Java type is translated into a database representation,
 * and it is the one piece of {@link FeederConfig} that silently accepts malformed input from the
 * database side. A bad value here surfaces much later, as a rule that never fires or fires twice.
 */
class DayOfWeekSetConverterTests {

	private final DayOfWeekSetConverter converter = new DayOfWeekSetConverter();

	/**
	 * Sorting is what keeps rows diffable. A caller may hand over a HashSet, an EnumSet or a stream
	 * result, and the stored string must be identical for the same logical set every time.
	 */
	@Test
	void convertToDatabaseColumn_shouldSortMondayToSunday_regardlessOfInsertionOrder() {
		Set<DayOfWeek> reversed = new LinkedHashSet<>();
		reversed.add(DayOfWeek.SUNDAY);
		reversed.add(DayOfWeek.FRIDAY);
		reversed.add(DayOfWeek.MONDAY);

		assertThat(converter.convertToDatabaseColumn(reversed)).isEqualTo("MON,FRI,SUN");
	}

	@Test
	void convertToDatabaseColumn_shouldWriteEveryDay_whenGivenTheFullWeek() {
		assertThat(converter.convertToDatabaseColumn(EnumSet.allOf(DayOfWeek.class)))
				.isEqualTo("MON,TUE,WED,THU,FRI,SAT,SUN");
	}

	/**
	 * The column is NOT NULL and the domain never produces an empty set, so an empty input can only
	 * come from outside. Returning null lets the database reject it rather than writing a value
	 * that would later parse into nothing.
	 */
	@Test
	void convertToDatabaseColumn_shouldReturnNull_whenEmpty() {
		assertThat(converter.convertToDatabaseColumn(null)).isNull();
		assertThat(converter.convertToDatabaseColumn(EnumSet.noneOf(DayOfWeek.class))).isNull();
	}

	/**
	 * Defensive parsing of whatever is already in the column. A stray space or a trailing comma
	 * must not take the whole rule out of service, so both are tolerated here rather than treated
	 * as corruption.
	 * <p>
	 * Note the two quoting rules of @CsvSource: the database value keeps single quotes so its commas
	 * are literal, while the expected value is space separated so it stays a single column.
	 */
	@ParameterizedTest
	@CsvSource({
			"'MON,TUE,WED', MONDAY TUESDAY WEDNESDAY",
			"'MON , TUE , WED', MONDAY TUESDAY WEDNESDAY",
			"'MON,,TUE', MONDAY TUESDAY",
			"'MON,', MONDAY",
			"',MON,', MONDAY"
	})
	void convertToEntityAttribute_shouldTolerateWhitespaceAndEmptyTokens(String dbData, String expected) {
		assertThat(converter.convertToEntityAttribute(dbData))
				.containsExactlyInAnyOrderElementsOf(expectedDays(expected));
	}

	@Test
	void convertToEntityAttribute_shouldThrowOnAnUnknownCode() {
		assertThatThrownBy(() -> converter.convertToEntityAttribute("MON,XYZ"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessage("unknown day of week code: XYZ");
	}

	@Test
	void convertToEntityAttribute_shouldReturnNull_whenBlank() {
		assertThat(converter.convertToEntityAttribute(null)).isNull();
		assertThat(converter.convertToEntityAttribute("   ")).isNull();
	}

	/**
	 * The round trip is the property that actually matters, and it is stronger than either
	 * direction alone: writing then reading must return the same set for every possible input.
	 */
	@Test
	void roundTrip_shouldReturnTheSameWeekdays_forAnyCombination() {
		for (int mask = 1; mask < 128; mask++) {
			Set<DayOfWeek> original = EnumSet.noneOf(DayOfWeek.class);
			DayOfWeek[] all = DayOfWeek.values();
			for (int bit = 0; bit < all.length; bit++) {
				if ((mask & (1 << bit)) != 0) {
					original.add(all[bit]);
				}
			}

			String stored = converter.convertToDatabaseColumn(original);

			assertThat(converter.convertToEntityAttribute(stored)).containsExactlyInAnyOrderElementsOf(original);
		}
	}

	private static Set<DayOfWeek> expectedDays(String spaceSeparated) {
		return Arrays.stream(spaceSeparated.split(" "))
				.map(DayOfWeek::valueOf)
				.collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
	}
}
