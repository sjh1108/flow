-- Per-table privileges for the application account.
--
-- Applied by the `grants` service in docker-compose.yml, which runs after the
-- migration has created the tables and before the application starts.
--
-- It deliberately lives outside mysql-init/. Everything in that directory is
-- mounted into /docker-entrypoint-initdb.d and runs on the database's very
-- first start -- before any table exists, which is the one moment these
-- statements cannot work. This file sat there anyway, while the README told
-- the operator to apply it by hand later.
--
-- It used to be a manual step, and the README said in so many words that it was
-- easy to forget. Forgetting it left the application account with whatever the
-- init script had granted it, which is to say everything -- the protection
-- below existed only if someone remembered to switch it on. Now it is a
-- container, and it re-asserts these grants on every deployment.
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
-- That argument only holds if the application cannot simply connect as someone
-- stronger. It used to be able to: the migrator's password sat in the app
-- container's own environment, so the account separation was a description of
-- the schema rather than a property of the deployment. Migration now runs in a
-- separate container and the application never receives those credentials.
--
-- Creating those rows is the migration account's job alone.

-- Empty the account completely, then grant back the list below. The file is
-- meant to leave the account at exactly this allowlist on every deployment, and
-- that only works if it can remove privileges it did not grant.
--
-- The form matters. `REVOKE ALL PRIVILEGES ON extguard.* FROM ...` touches only
-- the database level (mysql.db); table privileges live in mysql.tables_priv and
-- column privileges in mysql.columns_priv, and it leaves both alone. Dropping a
-- GRANT from this file would then not remove it from a database that already
-- had it -- as happened with flyway_schema_history, which this file stopped
-- granting while every existing deployment kept the privilege.
--
-- Without the ON clause, the statement revokes at every level, which is what
-- "re-converge on the allowlist" requires.
REVOKE ALL PRIVILEGES, GRANT OPTION FROM 'extguard_app'@'%';

-- Fixed extensions: read and toggle only. No INSERT. No DELETE.
GRANT SELECT, UPDATE ON extguard.fixed_extension_state TO 'extguard_app'@'%';

-- Custom extensions: added and removed by users, so full row control.
GRANT SELECT, INSERT, DELETE ON extguard.custom_extension TO 'extguard_app'@'%';

-- Append-only logs: the application must never rewrite history.
GRANT SELECT, INSERT ON extguard.policy_audit_log TO 'extguard_app'@'%';
GRANT SELECT, INSERT ON extguard.upload_record TO 'extguard_app'@'%';

-- The one exception, and it is deliberately the narrowest one MySQL allows.
-- The storage reclaim job deletes files past the retention period and has to
-- record that it did; a column-level grant lets it write purged_at and nothing
-- else. What was uploaded, what was refused and why all remain unwritable, so
-- "the application never rewrites history" still holds for every column that
-- carries history. No DELETE is granted: rows are marked, never removed.
GRANT UPDATE (purged_at) ON extguard.upload_record TO 'extguard_app'@'%';

-- No grant on flyway_schema_history. The application no longer runs Flyway, so
-- it has no reason to read it; the migrate container connects as the migrator,
-- which owns the table.

FLUSH PRIVILEGES;
