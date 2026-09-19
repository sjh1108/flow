-- Runs once, on first container start, before the application connects.
--
-- Two accounts with different powers:
--
--   extguard_migrator  owns the schema. Flyway connects as this account.
--   extguard_app       the running application. Deliberately weaker.
--
-- Only database-level grants are issued here, because the tables do not exist
-- yet at this point. The fine-grained, per-table grants that actually protect
-- the fixed extensions are in 02-grants.sql, which must be applied AFTER the
-- first migration has created the tables. See deploy/README.md.

CREATE DATABASE IF NOT EXISTS extguard
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'extguard_migrator'@'%' IDENTIFIED BY 'CHANGE_ME_MIGRATOR';
GRANT ALL PRIVILEGES ON extguard.* TO 'extguard_migrator'@'%';

CREATE USER IF NOT EXISTS 'extguard_app'@'%' IDENTIFIED BY 'CHANGE_ME_APP';
-- Interim broad grant so the application can start before 02-grants.sql is
-- applied. 02-grants.sql revokes this and replaces it with per-table rights.
GRANT SELECT, INSERT, UPDATE, DELETE ON extguard.* TO 'extguard_app'@'%';

FLUSH PRIVILEGES;
