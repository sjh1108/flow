-- Per-table privileges for the application account.
--
-- APPLY THIS AFTER THE FIRST MIGRATION has created the tables; MySQL cannot
-- grant on a table that does not exist yet. Running it again later is harmless.
--
--   docker compose exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
--       < deploy/mysql-init/02-grants.sql
--
-- WHY THIS EXISTS
--
-- The seven fixed extensions must stay fixed. Application code is already scoped
-- so that no code path can delete one, and CHECK constraints stop a fixed
-- extension being registered as a custom one. This file closes the remaining
-- gap: even if the application were compromised and made to issue arbitrary SQL,
-- its database account still cannot INSERT or DELETE rows in
-- fixed_extension_state. It can only flip the `blocked` column.
--
-- Creating those rows is the migration account's job alone.

REVOKE ALL PRIVILEGES ON extguard.* FROM 'extguard_app'@'%';

-- Fixed extensions: read and toggle only. No INSERT. No DELETE.
GRANT SELECT, UPDATE ON extguard.fixed_extension_state TO 'extguard_app'@'%';

-- Custom extensions: added and removed by users, so full row control.
GRANT SELECT, INSERT, DELETE ON extguard.custom_extension TO 'extguard_app'@'%';

-- Append-only logs: the application must never rewrite history.
GRANT SELECT, INSERT ON extguard.policy_audit_log TO 'extguard_app'@'%';
GRANT SELECT, INSERT ON extguard.upload_record TO 'extguard_app'@'%';

-- Flyway's own history table is read by the app at startup for validation.
GRANT SELECT ON extguard.flyway_schema_history TO 'extguard_app'@'%';

FLUSH PRIVILEGES;
