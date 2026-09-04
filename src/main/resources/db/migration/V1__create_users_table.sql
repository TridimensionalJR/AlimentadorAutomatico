create table users
(
    id         UUID PRIMARY KEY         NOT NULL,
    google_id  VARCHAR(255)             NOT NULL,
    email      VARCHAR(255)             NOT NULL,
    name       VARCHAR(255)             NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_users_google_id UNIQUE (google_id),
    CONSTRAINT uk_users_email UNIQUE (email)
)