-- Accounts, authenticated through Google OAuth.
--
-- A user is located on sign-in by google_id rather than by the primary key, so it is the natural
-- key for the whole table. Email is unique on its own account for that because it is the address
-- a person recognises as theirs.

create table users
(
    id         UUID PRIMARY KEY         NOT NULL,
    -- Subject claim of the Google ID token. Stable for the lifetime of the Google account.
    google_id  VARCHAR(255)             NOT NULL,
    email      VARCHAR(255)             NOT NULL,
    -- Display name. The OIDC name claim is optional, so sign-in is expected to fall back to the
    -- email local part rather than store a blank value.
    name       VARCHAR(255)             NOT NULL,
    -- Timestamps use the database default because they are written by Flyway's insert path and
    -- never by the application. JPA auditing keeps updated_at in step afterwards.
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE,
    -- Both uniqueness rules mirror the uniqueConstraints declared on the User entity.
    CONSTRAINT uk_users_google_id UNIQUE (google_id),
    CONSTRAINT uk_users_email UNIQUE (email)

)
