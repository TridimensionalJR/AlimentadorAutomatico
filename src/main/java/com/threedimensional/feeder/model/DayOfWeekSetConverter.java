package com.threedimensional.feeder.model;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.time.DayOfWeek;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Stores a {@code Set<DayOfWeek>} as a comma-separated string, for example {@code "MON,WED,FRI"}.
 * <p>
 * A join table would be the textbook relational answer, but a converter keeps everything in one
 * table and one migration, which matches this project's unidirectional style - no entity here maps
 * a collection, and rules are always loaded a feeder at a time and filtered in Java. The real
 * access pattern never needs to query "rules that run on Monday", so the extra table would buy
 * nothing. Revisit this if per-weekday overrides are ever introduced.
 */
@Converter
public class DayOfWeekSetConverter implements AttributeConverter<Set<DayOfWeek>, String> {

    private static final String SEPARATOR = ",";
    /**
     * Three-letter codes rather than the full enum names. {@code DayOfWeek.name()} would write
     * "MONDAY,TUESDAY,...,SUNDAY" - 56 characters, which does not fit the VARCHAR(50) column the
     * migration declares, and every INTERVAL rule writes all seven days, so every interval rule
     * would fail to persist. The three letters are unique across the enum, so the short form loses
     * no information.
     */
    private static final int CODE_LENGTH = 3;

    /**
     * Writes weekdays sorted Monday to Sunday so the stored value is stable no matter which
     * Set implementation the caller supplied, which keeps rows diffable.
     */
    @Override
    public String convertToDatabaseColumn(Set<DayOfWeek> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return null;
        }
        return attribute.stream()
                .sorted(Comparator.comparingInt(DayOfWeek::ordinal))
                .map(DayOfWeekSetConverter::toCode)
                .collect(Collectors.joining(SEPARATOR));
    }

    /**
     * Throws on an unrecognised code rather than skipping it. A silent drop would turn a corrupted
     * row into a rule that quietly runs on the wrong days, which is far harder to notice than a
     * failure at read time.
     */
    @Override
    public Set<DayOfWeek> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return Arrays.stream(dbData.split(SEPARATOR))
                .map(String::trim)
                .filter(day -> !day.isEmpty())
                .map(DayOfWeekSetConverter::toDayOfWeek)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }

    private static String toCode(DayOfWeek day) {
        return day.name().substring(0, CODE_LENGTH);
    }

    private static DayOfWeek toDayOfWeek(String code) {
        for (DayOfWeek day : DayOfWeek.values()) {
            if (toCode(day).equals(code)) {
                return day;
            }
        }
        throw new IllegalArgumentException("unknown day of week code: " + code);
    }
}
