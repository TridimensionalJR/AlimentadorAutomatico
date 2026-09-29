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
                .map(DayOfWeek::name)
                .collect(Collectors.joining(SEPARATOR));
    }

    @Override
    public Set<DayOfWeek> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return null;
        }
        return Arrays.stream(dbData.split(SEPARATOR))
                .map(String::trim)
                .filter(day -> !day.isEmpty())
                .map(DayOfWeek::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(DayOfWeek.class)));
    }
}
