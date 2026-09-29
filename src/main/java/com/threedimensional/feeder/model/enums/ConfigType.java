package com.threedimensional.feeder.model.enums;

/**
 * The two shapes a feeding rule can take. The value selects which of
 * {@code intervalMinutes} and {@code timeOfDay} carries the schedule, and which field
 * the other shape must leave null.
 */
public enum ConfigType {
    /** Repeats forever, every N minutes, continuously. Example: dispense every 4 hours. */
    INTERVAL,
    /** Fires at a fixed wall-clock time on the selected weekdays. Example: 12:00 and 18:00. */
    DAILY_TIME
}
