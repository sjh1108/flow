#!/usr/bin/env bash
#
# End-to-end proof that the extension policy is enforced on real uploads.
#
# Runs against a live API. The central assertion is the pair marked [CORE]:
# the same file uploads successfully while `exe` is unchecked, and is refused
# once it is checked. That is requirement B -- the policy screen actually
# governing uploads -- demonstrated over HTTP rather than only in tests.
#
# Usage:
#   scripts/verify.sh [BASE_URL]
#   ADMIN_TOKEN=... scripts/verify.sh https://api.example.com
#
set -uo pipefail

BASE_URL="${1:-http://localhost:8080}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

PASS=0
FAIL=0

green() { printf '\033[0;32m%s\033[0m' "$1"; }
red()   { printf '\033[0;31m%s\033[0m' "$1"; }
bold()  { printf '\033[1m%s\033[0m' "$1"; }

auth_args=()
[ -n "$ADMIN_TOKEN" ] && auth_args=(-H "X-Admin-Token: $ADMIN_TOKEN")

# check <description> <expected> <actual>
check() {
  local description="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    echo "  $(green '✓') $description"
    PASS=$((PASS + 1))
  else
    echo "  $(red '✗') $description"
    echo "      expected: $expected"
    echo "      actual:   $actual"
    FAIL=$((FAIL + 1))
  fi
}

# contains <description> <needle> <haystack>
contains() {
  local description="$1" needle="$2" haystack="$3"
  if [[ "$haystack" == *"$needle"* ]]; then
    echo "  $(green '✓') $description"
    PASS=$((PASS + 1))
  else
    echo "  $(red '✗') $description"
    echo "      expected to contain: $needle"
    echo "      actual:              ${haystack:0:300}"
    FAIL=$((FAIL + 1))
  fi
}

# upload <filename> -> "<http_status>|<body>"
upload() {
  curl -sS -o "$WORK_DIR/body" -w '%{http_code}' \
    -F "files=@$WORK_DIR/$1;filename=$1" \
    "$BASE_URL/api/v1/files" 2>/dev/null
  echo "|$(cat "$WORK_DIR/body")"
}

status_of()  { echo "${1%%|*}"; }
body_of()    { echo "${1#*|}"; }

set_fixed() {
  curl -sS -o /dev/null -w '%{http_code}' -X PATCH \
    -H 'Content-Type: application/json' "${auth_args[@]}" \
    -d "{\"blocked\": $2}" \
    "$BASE_URL/api/v1/policy/extensions/fixed/$1"
}

add_custom() {
  curl -sS -o "$WORK_DIR/body" -w '%{http_code}' -X POST \
    -H 'Content-Type: application/json' "${auth_args[@]}" \
    -d "{\"extension\": \"$1\"}" \
    "$BASE_URL/api/v1/policy/extensions/custom"
}

delete_custom() {
  curl -sS -o /dev/null -w '%{http_code}' -X DELETE "${auth_args[@]}" \
    "$BASE_URL/api/v1/policy/extensions/custom/$1"
}

# ---------------------------------------------------------------- fixtures
# hello.exe carries the PE magic number (MZ), not text. These are magic-number
# prefixes rather than valid executables -- the detector matches leading bytes
# only, so a prefix is what exercises it. An earlier version used text content,
# so the core scenario below passed while the exe checkbox had no effect at all.
printf 'MZ\x90\x00\x03\x00\x00\x00'     > "$WORK_DIR/hello.exe"
printf 'MZ\x90\x00\x03\x00\x00\x00'     > "$WORK_DIR/payload"
printf 'MZ\x90\x00\x03\x00\x00\x00'     > "$WORK_DIR/report.jpg"
printf 'MZ\x90\x00\x03\x00\x00\x00'     > "$WORK_DIR/installer.msi"
printf 'hello, world\n'                 > "$WORK_DIR/notes.txt"
printf 'hello, world\n'                 > "$WORK_DIR/invoice.pdf.exe"
printf '#!/bin/bash\necho hi\n'         > "$WORK_DIR/deploy.sh"
printf '\x89PNG\r\n\x1a\n rest'         > "$WORK_DIR/avatar.png"

echo
bold "확장자 차단 시스템 검증"; echo
echo "대상: $BASE_URL"
[ -z "$ADMIN_TOKEN" ] && echo "(ADMIN_TOKEN 미설정 — 서버에 토큰이 설정되어 있으면 정책 변경이 401로 실패합니다)"
echo

# ------------------------------------------------------------------ health
bold "0. 헬스체크"; echo
health=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE_URL/actuator/health" 2>/dev/null)
check "actuator/health 응답" "200" "$health"
if [ "$health" != "200" ]; then
  echo; red "서버에 연결할 수 없어 중단합니다."; echo; exit 1
fi

# ------------------------------------------------------- baseline policy
echo; bold "1. 초기 정책 상태"; echo
policy=$(curl -sS "$BASE_URL/api/v1/policy/extensions")
fixed_count=$(echo "$policy" | grep -o '"extension"' | wc -l | tr -d ' ')
contains "고정 확장자 7개가 모두 존재" '"exe"' "$policy"
contains "기본값은 차단 해제 상태" '"blocked":false' "$policy"
contains "제한값이 응답에 포함" '"maxCustomExtensions":200' "$policy"

# --------------------------------------------------------- [CORE] A -> B
echo; bold "2. [CORE] 정책이 실제 업로드에 강제되는지"; echo

set_fixed exe false > /dev/null
result=$(upload hello.exe)
check "exe 미체크 상태에서 PE 시그니처 파일 업로드 성공" "200" "$(status_of "$result")"
contains "결과가 ACCEPTED" '"status":"ACCEPTED"' "$(body_of "$result")"

code=$(set_fixed exe true)
check "exe 확장자 체크 (PATCH)" "200" "$code"

result=$(upload hello.exe)
check "동일 파일 재업로드가 422로 거부" "422" "$(status_of "$result")"
contains "거부 코드가 EXTENSION_BLOCKED" '"code":"EXTENSION_BLOCKED"' "$(body_of "$result")"
contains "사유에 exe가 명시됨" 'exe' "$(body_of "$result")"

result=$(upload notes.txt)
check "차단되지 않은 파일은 여전히 업로드 성공" "200" "$(status_of "$result")"

# ------------------------------------------------------------ evasion
echo; bold "3. 우회 시도 차단"; echo

result=$(upload invoice.pdf.exe)
check "이중 확장자 invoice.pdf.exe 거부" "422" "$(status_of "$result")"
contains "확장자 체인 [pdf,exe]가 사유에 포함" 'pdf,exe' "$(body_of "$result")"

set_fixed exe false > /dev/null
result=$(upload report.jpg)
check "내용이 실행파일인 report.jpg 거부" "422" "$(status_of "$result")"
contains "거부 코드가 EXECUTABLE_CONTENT" '"code":"EXECUTABLE_CONTENT"' "$(body_of "$result")"
contains "탐지된 시그니처가 PE_EXE" 'PE_EXE' "$(body_of "$result")"

result=$(upload payload)
check "확장자 없는 실행파일 거부" "422" "$(status_of "$result")"
contains "사유가 확장자 부재를 설명" '확장자가 없' "$(body_of "$result")"

# MSI is an OLE compound document, not PE. A PE payload named .msi is a disguise.
result=$(upload installer.msi)
check "PE 내용을 .msi로 위장한 파일 거부" "422" "$(status_of "$result")"

result=$(upload avatar.png)
check "실제 PNG는 정상 업로드" "200" "$(status_of "$result")"

# ------------------------------------------------------- custom policy
echo; bold "4. 커스텀 확장자"; echo

delete_custom sh > /dev/null 2>&1
result=$(upload deploy.sh)
check "sh 미등록 상태에서 deploy.sh 업로드 성공" "200" "$(status_of "$result")"

code=$(add_custom sh)
check "커스텀 확장자 sh 추가" "201" "$code"

result=$(upload deploy.sh)
check "sh 등록 후 deploy.sh 거부" "422" "$(status_of "$result")"
contains "사유에 커스텀 차단 목록 명시" '커스텀' "$(body_of "$result")"

code=$(add_custom sh)
check "중복 추가는 409" "409" "$code"
contains "중복 코드가 EXT_DUPLICATE" 'EXT_DUPLICATE' "$(cat "$WORK_DIR/body")"

code=$(add_custom SH)
check "대문자 SH도 중복으로 처리" "409" "$code"

code=$(add_custom exe)
check "고정 확장자를 커스텀으로 추가 시 409" "409" "$code"
contains "코드가 EXT_IS_FIXED" 'EXT_IS_FIXED' "$(cat "$WORK_DIR/body")"

code=$(add_custom "aaaaaaaaaaaaaaaaaaaaa")   # 21 chars
check "21자 확장자는 400" "400" "$code"
contains "코드가 EXT_TOO_LONG" 'EXT_TOO_LONG' "$(cat "$WORK_DIR/body")"

code=$(delete_custom sh)
check "커스텀 확장자 삭제" "200" "$code"

result=$(upload deploy.sh)
check "삭제 후 deploy.sh 다시 업로드 성공" "200" "$(status_of "$result")"

# ------------------------------------------- fixed extension integrity
echo; bold "5. 고정 확장자 무결성"; echo

code=$(delete_custom exe)
check "고정 확장자를 커스텀 삭제 API로 지우려 하면 404" "404" "$code"

policy=$(curl -sS "$BASE_URL/api/v1/policy/extensions")
after_count=$(echo "$policy" | grep -o '"blocked"' | wc -l | tr -d ' ')
check "삭제 시도 후에도 고정 확장자는 7개" "7" "$after_count"

code=$(curl -sS -o "$WORK_DIR/body" -w '%{http_code}' -X DELETE "${auth_args[@]}" \
  "$BASE_URL/api/v1/policy/extensions/fixed/exe")
check "고정 확장자 삭제 엔드포인트는 존재하지 않음 (405)" "405" "$code"
contains "405가 서버 오류가 아닌 클라이언트 오류로 보고됨" 'METHOD_NOT_ALLOWED' "$(cat "$WORK_DIR/body")"

code=$(curl -sS -o /dev/null -w '%{http_code}' -X PATCH \
  -H 'Content-Type: application/json' "${auth_args[@]}" \
  -d '{"blocked": true}' "$BASE_URL/api/v1/policy/extensions/fixed/sh")
check "고정 목록에 없는 확장자 토글은 404" "404" "$code"

# ------------------------------------------------------------- cleanup
set_fixed exe false > /dev/null

# -------------------------------------------------------------- summary
echo
bold "결과"; echo
echo "  통과: $(green "$PASS")   실패: $([ "$FAIL" -eq 0 ] && echo "$FAIL" || red "$FAIL")"
echo
[ "$FAIL" -eq 0 ] || exit 1
