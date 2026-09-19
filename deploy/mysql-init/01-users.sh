#!/bin/bash
# Runs once, on first container start, before anything connects.
#
# A shell script rather than a .sql file so the passwords can come from the
# environment. The MySQL image executes *.sh in /docker-entrypoint-initdb.d as
# well as *.sql, and only the shell form can read a variable -- a .sql file
# would have to carry the passwords as literals in a committed file, which the
# operator then edits and risks committing back.
#
# Two accounts with different powers:
#
#   extguard_migrator  owns the schema. Only the migrate container connects as
#                      this account, and only for as long as it takes to run.
#   extguard_app       the running application. Deliberately weaker, and never
#                      given database-wide rights at any point -- grants.sql
#                      is applied before the application first starts, so there
#                      is no window to cover with a temporary broad grant.
#
# Only the migrator's grants are issued here. The application's are per-table
# and the tables do not exist yet; see grants.sql.
set -euo pipefail

: "${MIGRATOR_PASSWORD:?MIGRATOR_PASSWORD must be set for the mysql service}"
: "${APP_DB_PASSWORD:?APP_DB_PASSWORD must be set for the mysql service}"

# The passwords below are interpolated into SQL string literals, so a quote or a
# backslash would not be a weak password -- it would be broken SQL, and the
# database would come up without the accounts. Rejecting the characters is
# simpler and more predictable than escaping for shell, Compose and SQL at once,
# and it fails here with a clear message instead of somewhere downstream.
#
# The permitted set still allows as much entropy as anyone needs:
# `openssl rand -hex 32` is 128 bits and passes.
require_safe_password() { # name, value
    case "$2" in
        "")
            echo "$1 is empty" >&2
            exit 1
            ;;
        *[!A-Za-z0-9_-]*)
            echo "$1 must use only letters, digits, '_' and '-'." >&2
            echo "Anything else would have to be escaped for the shell, for" >&2
            echo "Compose and for SQL. Generate one with: openssl rand -hex 32" >&2
            exit 1
            ;;
    esac
}

require_safe_password MIGRATOR_PASSWORD "$MIGRATOR_PASSWORD"
require_safe_password APP_DB_PASSWORD "$APP_DB_PASSWORD"

MYSQL_PWD="${MYSQL_ROOT_PASSWORD}" mysql --protocol=socket -uroot <<SQL
CREATE DATABASE IF NOT EXISTS extguard
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'extguard_migrator'@'%' IDENTIFIED BY '${MIGRATOR_PASSWORD}';
GRANT ALL PRIVILEGES ON extguard.* TO 'extguard_migrator'@'%';

-- Created with no schema privileges whatsoever. grants.sql gives it exactly
-- what it needs, and runs before the application does.
CREATE USER IF NOT EXISTS 'extguard_app'@'%' IDENTIFIED BY '${APP_DB_PASSWORD}';

FLUSH PRIVILEGES;
SQL
