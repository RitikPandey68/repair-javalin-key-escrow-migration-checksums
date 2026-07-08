-- H2 migration schema for the key-escrow custody replay.
-- Applied idempotently at the start of every replay so a fresh in-container run
-- and a re-run against an existing ./data/migration store behave identically.

CREATE TABLE IF NOT EXISTS key_versions (
    key_id        VARCHAR(128) NOT NULL,
    version       INT          NOT NULL,
    algorithm     VARCHAR(64)  NOT NULL,
    custody_status VARCHAR(64) NOT NULL,
    created_epoch BIGINT       NOT NULL,
    PRIMARY KEY (key_id, version)
);

CREATE TABLE IF NOT EXISTS wrapping_certificates (
    cert_id        VARCHAR(128) NOT NULL,
    subject        VARCHAR(512) NOT NULL,
    key_id         VARCHAR(128) NOT NULL,
    not_after_epoch BIGINT      NOT NULL,
    PRIMARY KEY (cert_id)
);

CREATE TABLE IF NOT EXISTS custody_grants (
    grant_id      VARCHAR(128) NOT NULL,
    principal     VARCHAR(256) NOT NULL,
    cert_id       VARCHAR(128) NOT NULL,
    revoked       BOOLEAN      NOT NULL,
    granted_epoch BIGINT       NOT NULL,
    PRIMARY KEY (grant_id)
);
