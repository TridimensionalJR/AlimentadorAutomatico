-- Scheduling: the rules that drive a feeder, and the zone they are interpreted in.
--
-- A row in feeder_configs is one of two shapes, told apart by `type`:
--   INTERVAL   -> interval_minutes set, time_of_day null (repeats every N minutes, continuously)
--   DAILY_TIME -> time_of_day set, interval_minutes null (fires at a wall-clock time on `weekdays`)
--
-- The two shapes are mutually exclusive by design. That single rule is what makes the two
-- UNIQUE constraints at the bottom work, so it is enforced here rather than left to callers.
--
-- Times are stored without a zone on purpose: a recurring wall-clock time is a pattern, not an
-- event, so it carries no date and no offset. The zone lives on the owning feeder, which keeps
-- one value authoritative for all of its rules.

-- The zone arrives with scheduling rather than in V2: before this migration a feeder only had an
-- identity and a hardware pairing, and no rule referred to any time of day. NOT NULL with a
-- default backfills existing feeders in place, so none needs a follow-up data migration.
alter table feeders
    add column timezone VARCHAR(64) NOT NULL DEFAULT 'America/Bahia';

-- Mirrors ck_feeder_configs_weekdays. NOT NULL already rejects a missing zone; this rejects a blank
-- one, which only a raw insert bypassing the domain validation could produce. Adding the column
-- with a non-empty default means existing feeders already satisfy it, so this cannot fail.
alter table feeders
    add constraint ck_feeders_timezone CHECK (timezone <> '');

create table feeder_configs
(
    id               UUID PRIMARY KEY         NOT NULL,
    -- Schedule discriminator. Stored as text rather than a native enum so the same DDL runs on
    -- H2 in development and PostgreSQL in production.
    type             VARCHAR(255)            NOT NULL,
    -- Elapsed time between runs. Required for INTERVAL, null for DAILY_TIME.
    interval_minutes INTEGER,
    -- Wall-clock time, no date and no offset. Required for DAILY_TIME, null for INTERVAL.
    time_of_day      TIME,
    -- Comma-separated, Monday first, e.g. 'MON,WED,FRI'. Never empty: INTERVAL is forced to
    -- all seven days because it repeats continuously.
    weekdays         VARCHAR(50)             NOT NULL,
    -- Motor activations per trigger. Bounded below to keep the auger from jamming.
    dose             INTEGER                 NOT NULL,
    -- Anchor for the next INTERVAL run, null until the first execution. Persisting it is what
    -- lets an interval survive a backend restart instead of losing its count.
    last_run_at      TIMESTAMP WITH TIME ZONE,
    is_active        BOOLEAN                 NOT NULL DEFAULT TRUE,
    feeder_id        UUID                    NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP WITH TIME ZONE,
    -- Mutual exclusivity of the two schedule shapes. Also guarantees one of the two columns is
    -- always null, which the UNIQUE constraints below rely on.
    CONSTRAINT ck_feeder_configs_spec CHECK (
        (type = 'INTERVAL' AND interval_minutes IS NOT NULL AND time_of_day IS NULL)
            OR (type = 'DAILY_TIME' AND time_of_day IS NOT NULL AND interval_minutes IS NULL)
        ),
    -- One minute floor: a zero interval would stall the scheduler, which could never advance to
    -- a next run. Seven-day ceiling, mirroring FeederConfig.MAX_INTERVAL_MINUTES.
    CONSTRAINT ck_feeder_configs_interval_minutes CHECK (interval_minutes IS NULL OR interval_minutes BETWEEN 1 AND 10080),
    -- Ceiling mirrors FeederConfig.MAX_DOSE, generous over the firmware's maximum of 25.
    CONSTRAINT ck_feeder_configs_dose CHECK (dose BETWEEN 1 AND 100),
    CONSTRAINT ck_feeder_configs_weekdays CHECK (weekdays <> ''),
    -- One timed rule per feeder per time of day, weekdays intentionally ignored: two rules at
    -- 12:00 would double-dispense on any day they overlap. Same shape for intervals below.
    -- Both constraints work as written because SQL treats NULL as distinct inside UNIQUE, so the
    -- null column of a row never collides with anything - no partial index required, which H2
    -- would not support anyway.
    CONSTRAINT uk_feeder_configs_time UNIQUE (feeder_id, time_of_day),
    CONSTRAINT uk_feeder_configs_interval UNIQUE (feeder_id, interval_minutes),
    -- CASCADE rather than RESTRICT, the opposite of feeders -> users: deleting a feeder should
    -- take its rules with it, whereas a user must not disappear while still owning feeders.
    CONSTRAINT fk_feeder_configs_feeder FOREIGN KEY (feeder_id) REFERENCES feeders (id) ON DELETE CASCADE

)
