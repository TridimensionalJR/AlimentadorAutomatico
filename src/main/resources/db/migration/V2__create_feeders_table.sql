-- Feeders: the physical devices, before any scheduling is attached to them.
--
-- Scheduling rules arrive in V3, along with the timezone column those times are interpreted in.
-- That column deliberately does not live here: before V3 no rule referred to a time of day, so
-- keeping it out leaves this migration describing only what a feeder *is*.

create table feeders
(
    id          UUID PRIMARY KEY         NOT NULL,
    -- Reported by the hardware. Unique across the whole table, so one device can only ever be
    -- paired with one feeder.
    device_id   UUID                     NOT NULL,
    name        VARCHAR(255)             NOT NULL,
    description VARCHAR(255),
    is_active   BOOLEAN                  NOT NULL DEFAULT TRUE,
    -- The owning account.
    user_id     UUID                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_feeders_device_id UNIQUE (device_id),
    -- RESTRICT, the opposite of feeder_configs -> feeders in V3: a user must not disappear while
    -- still owning feeders, whereas deleting a feeder should take its own rules with it.
    CONSTRAINT fk_feeders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT

)
