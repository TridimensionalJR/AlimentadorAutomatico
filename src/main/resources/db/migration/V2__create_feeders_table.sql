create table feeders
(
    id          UUID PRIMARY KEY         NOT NULL,
    device_id   UUID                     NOT NULL,
    name        VARCHAR(255)             NOT NULL,
    description VARCHAR(255),
    is_active   BOOLEAN                  NOT NULL DEFAULT TRUE,
    user_id     UUID                     NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_feeders_device_id UNIQUE (device_id),
    CONSTRAINT fk_feeders_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT

)