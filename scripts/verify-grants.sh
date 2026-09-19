#!/usr/bin/env bash
# Proves the database privilege boundary on a real MySQL.
#
# Everything this project claims about privilege separation is MySQL-specific:
# table- and column-level grants, and the fact that the application container
# holds no credentials for a stronger account. None of it is expressible in H2,
# so the JVM suite -- however many cases it grows to -- cannot speak to it. One
# run of this is more direct evidence than all of them.
#
# The negative cases are the real assertions. Checking only that the permitted
# statements succeed would pass just as happily against an account granted
# everything, which is the exact failure this boundary exists to prevent.
#
#   scripts/verify-grants.sh
#
# Needs Docker. Brings the deploy stack up and tears it down again.
set -uo pipefail

cd "$(dirname "$0")/.."

ROOT_PW=rootpw0123456789
MIGRATOR_PW=migratorpw0123456789
APP_PW=apppw0123456789

# Distinct passwords, so "the app could do X" can never turn out to be the
# migrator doing it. Alphanumeric by design -- mysql-init/01-users.sh rejects
# anything that would need quoting.
ENV_FILE="$(mktemp)"
cat > "$ENV_FILE" <<ENV
MYSQL_ROOT_PASSWORD=$ROOT_PW
MIGRATOR_PASSWORD=$MIGRATOR_PW
APP_DB_PASSWORD=$APP_PW
EXTGUARD_ADMIN_TOKEN=
CORS_ALLOWED_ORIGINS=http://localhost:5173
API_DOMAIN=localhost
ENV

compose() { docker compose --env-file "$ENV_FILE" -f deploy/docker-compose.yml "$@"; }

cleanup() {
  compose down -v --remove-orphans >/dev/null 2>&1 || true
  rm -f "$ENV_FILE"
}
trap cleanup EXIT

GREEN='\033[0;32m'; RED='\033[0;31m'; BOLD='\033[1m'; OFF='\033[0m'
pass=0; fail=0

check() { # ok(0/1), description, detail
  if [ "$1" -eq 0 ]; then
    printf "  ${GREEN}✓${OFF} %s\n" "$2"; pass=$((pass + 1))
  else
    printf "  ${RED}✗${OFF} %s\n" "$2"
    [ -n "${3:-}" ] && printf "      %s\n" "$3"
    fail=$((fail + 1))
  fi
}

sql_as() { # user, password, statement
  compose exec -T -e MYSQL_PWD="$2" mysql \
    mysql -h mysql -u "$1" extguard -N -B -e "$3" 2>&1
}

app_can() { # statement, description
  local out status
  out=$(sql_as extguard_app "$APP_PW" "$1"); status=$?
  check "$status" "$2" "$out"
}

app_cannot() { # statement, description
  local out status
  out=$(sql_as extguard_app "$APP_PW" "$1"); status=$?
  if [ "$status" -eq 0 ]; then
    check 1 "$2" "문이 성공했습니다 — 권한이 열려 있습니다"
  elif printf '%s' "$out" | grep -qi "denied"; then
    check 0 "$2"
  else
    # Without this, a typo in the statement or a renamed table would read as
    # "the boundary held" -- the test would pass for the wrong reason.
    check 1 "$2" "거부는 됐지만 권한 때문이 아닙니다: $out"
  fi
}

printf "\n${BOLD}권한 경계 검증${OFF}\n"
printf "  compose 스택을 띄웁니다 (앱 이미지 빌드 포함, 몇 분 걸립니다)\n\n"

if ! compose up -d --build; then
  printf "${RED}스택 기동 실패${OFF}\n"
  compose ps -a
  compose logs migrate grants 2>&1 | tail -40
  exit 1
fi

for _ in $(seq 1 90); do
  curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1 && break
  sleep 2
done

printf "${BOLD}1. 3단계 부팅${OFF}\n"

# A one-shot that never exits holds the whole deployment behind it, so the exit
# status is read rather than inferred from the app being up.
for svc in migrate grants; do
  cid=$(compose ps -aq "$svc")
  code=$(docker inspect -f '{{.State.ExitCode}}' "$cid" 2>/dev/null)
  [ "$code" = "0" ]; check $? "$svc 컨테이너가 0으로 종료" \
    "종료 코드: ${code:-읽을 수 없음}$(printf '\n      ')$(compose logs "$svc" 2>&1 | tail -15)"
done

# If 01-users.sh had not run, these accounts would not exist. That a .sh in the
# init directory executes at all was previously an assumption from docs.
accounts=$(compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql \
  mysql -h mysql -uroot -N -B -e \
  "SELECT user FROM mysql.user WHERE user LIKE 'extguard%' ORDER BY user;" 2>&1)
printf '%s' "$accounts" | grep -q extguard_app; check $? \
  "01-users.sh가 실행되어 extguard_app 생성" "$accounts"
printf '%s' "$accounts" | grep -q extguard_migrator; check $? \
  "extguard_migrator 생성" "$accounts"

health=$(curl -sf http://localhost:8080/actuator/health 2>&1)
printf '%s' "$health" | grep -q '"status":"UP"'; check $? \
  "앱이 앱 계정만으로 기동 (Flyway 없이)" "$health"

printf "\n${BOLD}2. 앱 계정이 할 수 있어야 하는 것${OFF}\n"
app_can "SELECT COUNT(*) FROM fixed_extension_state;" "고정 확장자 조회"
app_can "UPDATE fixed_extension_state SET blocked = TRUE WHERE extension = 'exe';" "고정 확장자 토글"
app_can "INSERT INTO custom_extension (extension, created_at) VALUES ('tmpchk', NOW(6));" "커스텀 확장자 추가"
app_can "DELETE FROM custom_extension WHERE extension = 'tmpchk';" "커스텀 확장자 삭제"
app_can "INSERT INTO upload_record (original_filename, display_filename, size_bytes, status, created_at) VALUES ('a.txt', 'a.txt', 1, 'ACCEPTED', NOW(6));" "업로드 기록 추가"
app_can "UPDATE upload_record SET purged_at = NOW(6) WHERE original_filename = 'a.txt';" "purged_at 기록 (정리 작업이 쓰는 유일한 컬럼)"

printf "\n${BOLD}3. 앱 계정이 할 수 없어야 하는 것${OFF}\n"
printf "  ${BOLD}여기가 실제 단언입니다${OFF} — 권한을 전부 열어도 2절은 그대로 통과합니다\n"
app_cannot "INSERT INTO fixed_extension_state (extension, blocked, updated_at) VALUES ('sh', FALSE, NOW(6));" "고정 확장자를 새로 만들 수 없음"
app_cannot "DELETE FROM fixed_extension_state WHERE extension = 'exe';" "고정 확장자를 지울 수 없음"
app_cannot "UPDATE upload_record SET original_filename = 'rewritten.txt';" "업로드 이력을 고쳐 쓸 수 없음 (purged_at 외 컬럼)"
app_cannot "DELETE FROM upload_record;" "업로드 이력을 지울 수 없음"
app_cannot "UPDATE policy_audit_log SET actor = 'forged';" "감사 로그를 고쳐 쓸 수 없음"
app_cannot "SELECT COUNT(*) FROM flyway_schema_history;" "마이그레이션 이력을 읽을 수 없음"
app_cannot "ALTER TABLE custom_extension ADD COLUMN injected INT;" "스키마를 바꿀 수 없음"
app_cannot "DROP TABLE custom_extension;" "테이블을 지울 수 없음"

printf "\n${BOLD}4. 부여된 권한 목록${OFF}\n"
grants=$(compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql \
  mysql -h mysql -uroot -N -B -e "SHOW GRANTS FOR 'extguard_app'@'%';" 2>&1)
printf "%s\n" "$grants" | sed 's/^/      /'

# A database-wide grant makes every table-level restriction above moot, and that
# is exactly what the old deployment left behind when the manual step was
# skipped. USAGE is the empty grant MySQL always reports; it is not a privilege.
printf '%s' "$grants" | grep -v 'USAGE ON' | grep -q 'ON `extguard`\.\*'
[ $? -ne 0 ]; check $? "extguard.* 전체에 대한 권한이 없음"

printf '%s' "$grants" | grep -q flyway_schema_history
[ $? -ne 0 ]; check $? "flyway_schema_history 권한이 남아 있지 않음"

printf "\n${BOLD}5. 재적용이 남은 권한을 거둬들이는가${OFF}\n"
printf "  위 네 절은 새 데이터베이스만 봅니다 — 지울 것이 애초에 없으니\n"
printf "  \"제거한다\"는 주장은 시험되지 않습니다. 권한을 일부러 넓혀 두고 확인합니다.\n"

# The exact shape the old deployment left behind: a table-level SELECT this file
# stopped granting, plus a database-wide grant. `REVOKE ... ON extguard.*` clears
# only the second; the first is why the statement had to lose its ON clause.
compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql mysql -h mysql -uroot -e "
  GRANT SELECT ON extguard.flyway_schema_history TO 'extguard_app'@'%';
  GRANT SELECT, INSERT, UPDATE, DELETE ON extguard.* TO 'extguard_app'@'%';
  FLUSH PRIVILEGES;" >/dev/null 2>&1

widened=$(compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql \
  mysql -h mysql -uroot -N -B -e "SHOW GRANTS FOR 'extguard_app'@'%';" 2>&1)
printf '%s' "$widened" | grep -q flyway_schema_history; check $? \
  "(준비) 권한이 실제로 넓어졌는지" "$widened"

compose up -d --no-deps --force-recreate grants >/dev/null 2>&1
for _ in $(seq 1 30); do
  state=$(docker inspect -f '{{.State.Status}}' "$(compose ps -aq grants)" 2>/dev/null)
  [ "$state" = "exited" ] && break
  sleep 1
done

after=$(compose exec -T -e MYSQL_PWD="$ROOT_PW" mysql \
  mysql -h mysql -uroot -N -B -e "SHOW GRANTS FOR 'extguard_app'@'%';" 2>&1)
printf "%s\n" "$after" | sed 's/^/      /'

printf '%s' "$after" | grep -q flyway_schema_history
[ $? -ne 0 ]; check $? "재적용이 테이블 단위 권한을 거둬들임" "$after"

printf '%s' "$after" | grep -v 'USAGE ON' | grep -q 'ON `extguard`\.\*'
[ $? -ne 0 ]; check $? "재적용이 데이터베이스 단위 권한을 거둬들임" "$after"

app_cannot "SELECT COUNT(*) FROM flyway_schema_history;" "거둬들인 뒤 실제로도 거부됨"

printf "\n${BOLD}결과${OFF}  통과: ${GREEN}%d${OFF}  실패: %d\n\n" "$pass" "$fail"

# Dumped here rather than by the caller: the trap tears the stack down on exit,
# so by the time a CI step could look, the containers are gone.
if [ "$fail" -ne 0 ]; then
  printf "${BOLD}컨테이너 상태${OFF}\n"
  compose ps -a
  for svc in mysql migrate grants app; do
    printf "\n${BOLD}--- %s ---${OFF}\n" "$svc"
    compose logs "$svc" 2>&1 | tail -60
  done
fi

[ "$fail" -eq 0 ]
