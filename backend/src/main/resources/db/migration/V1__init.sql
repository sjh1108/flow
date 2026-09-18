-- Extension blocking policy + upload audit schema.
--
-- PORTABILITY: this file is executed by Flyway against both MySQL 8.4 (production)
-- and H2 in MySQL compatibility mode (tests), so that the CHECK constraints below
-- are exercised by the test suite rather than only in production. Engine and
-- charset are therefore NOT declared here -- InnoDB is the MySQL 8 default and
-- utf8mb4 is set at the database level in deploy/mysql-init/01-init.sql.

-- ---------------------------------------------------------------------------
-- Fixed extensions.
--
-- Exactly seven rows exist, created here and never by the application. The app
-- holds SELECT + UPDATE on this table only (see deploy/mysql-init/01-init.sql),
-- so it can toggle `blocked` but cannot insert or delete rows.
--
-- ck_fixed_whitelist must stay in sync with FixedExtensions.ALL in Java.
-- FixedExtensionsIntegrityTest fails if the two ever drift apart.
-- ---------------------------------------------------------------------------
CREATE TABLE fixed_extension_state (
    extension  VARCHAR(20) NOT NULL,
    blocked    BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_fixed_extension_state PRIMARY KEY (extension),
    CONSTRAINT ck_fixed_whitelist
        CHECK (extension IN ('bat', 'cmd', 'com', 'cpl', 'exe', 'scr', 'js'))
);

-- ---------------------------------------------------------------------------
-- Custom extensions.
--
-- ck_custom_not_fixed is the defence against a fixed extension being smuggled in
-- as a custom one -- including by someone with direct database access, which the
-- application-level check alone cannot stop.
-- ---------------------------------------------------------------------------
CREATE TABLE custom_extension (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    extension  VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT pk_custom_extension PRIMARY KEY (id),
    CONSTRAINT uk_custom_extension_extension UNIQUE (extension),
    CONSTRAINT ck_custom_not_fixed
        CHECK (extension NOT IN ('bat', 'cmd', 'com', 'cpl', 'exe', 'scr', 'js'))
);

CREATE INDEX idx_custom_extension_created_at ON custom_extension (created_at);

-- ---------------------------------------------------------------------------
-- Policy change history: who changed what, when, from which value to which.
-- ---------------------------------------------------------------------------
CREATE TABLE policy_audit_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    action         VARCHAR(30)  NOT NULL,
    extension      VARCHAR(20)  NOT NULL,
    extension_type VARCHAR(10)  NOT NULL,
    before_value   VARCHAR(20),
    after_value    VARCHAR(20),
    actor          VARCHAR(100) NOT NULL,
    actor_ip       VARCHAR(45),
    user_agent     VARCHAR(255),
    created_at     DATETIME(6)  NOT NULL,
    CONSTRAINT pk_policy_audit_log PRIMARY KEY (id)
);

CREATE INDEX idx_policy_audit_log_created_at ON policy_audit_log (created_at);
CREATE INDEX idx_policy_audit_log_extension ON policy_audit_log (extension);

-- ---------------------------------------------------------------------------
-- Upload attempts, accepted and rejected alike.
--
-- stored_name is a UUID: the original filename is recorded for display only and
-- is never used to build a path on disk.
-- ---------------------------------------------------------------------------
CREATE TABLE upload_record (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    stored_name           VARCHAR(64),
    original_filename     VARCHAR(255) NOT NULL,
    display_filename      VARCHAR(255) NOT NULL,
    extension_chain       VARCHAR(255),
    effective_extension   VARCHAR(20),
    size_bytes            BIGINT       NOT NULL,
    sha256                VARCHAR(64),
    declared_content_type VARCHAR(120),
    detected_signature    VARCHAR(40),
    status                VARCHAR(12)  NOT NULL,
    rejection_code        VARCHAR(40),
    rejection_detail      VARCHAR(500),
    client_ip             VARCHAR(45),
    created_at            DATETIME(6)  NOT NULL,
    CONSTRAINT pk_upload_record PRIMARY KEY (id),
    CONSTRAINT ck_upload_status CHECK (status IN ('ACCEPTED', 'REJECTED'))
);

CREATE INDEX idx_upload_record_created_at ON upload_record (created_at);
CREATE INDEX idx_upload_record_status ON upload_record (status, created_at);
