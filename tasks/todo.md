# Todo

현재 작업의 계획과 결과를 적는다. 완료된 작업은 PR 머지 후 지운다.

---

## 현재: 저장소 고갈 방어 (PR)

### 상한

- [x] `StorageProperties`에 `quota`(10GB), `min-free-space`(1GB), `retention`(30d),
      `orphan-grace-period`(24h), `cleanup-cron` 추가
- [x] `StorageBudget` — 요청당 한 번 측정하고 배치 안에서 차감. 쿼터와 디스크 여유를 각각 검사
- [x] 사용량은 디스크가 아니라 DB 합계로 계산 (`sumLiveBytes`). 거부·purge된 행이
      빠지므로 감사 로그가 길어져도 합계가 커지지 않음 (보존 창에 갇히는 것은 아님 —
      삭제가 실패한 행은 계속 잡히고, 그게 의도)
- [x] `ApiErrorCode.STORAGE_QUOTA_EXCEEDED` (507) — 거부도 `REJECTED` 기록으로 남김
- [x] `UploadResponse.status()` — 전부 거부 + 사유가 전부 용량이면 507, 아니면 422

### 회수

- [x] `StorageMaintenanceService` — 보존 만료 정리 + 고아 스윕, `@Scheduled`
- [x] `@EnableScheduling` (새 의존성 없음, Quartz는 단일 인스턴스에 과함)
- [x] `V3__add_upload_purged_at.sql` — `purged_at` 컬럼 + `(status, purged_at, size_bytes)` 인덱스
- [x] `UploadRecordRepository`에 필요한 메서드만 추가 (`delete`는 여전히 없음)
- [x] `02-grants.sql` — `GRANT UPDATE (purged_at)` 컬럼 단위로만. append-only 유지

### 예방

- [x] `LocalFileStorage` — `.part`에 쓰고 `ATOMIC_MOVE`. 실패 시 임시 파일 삭제
- [x] 권한을 생성 시점 속성으로 지정 (이전에는 쓰기 후에 조여서 그동안 기본 권한이었음)

### 테스트

- [x] `LocalFileStorageTest` — 실패한 쓰기가 `.part`도 불완전한 `.bin`도 남기지 않음
- [x] `StorageQuotaIntegrationTest` — 507, 기록 남김, 배치가 같은 여유를 두 번 쓰지 못함
- [x] `StorageMaintenanceIntegrationTest` — 보존/고아/유예/멱등
- [x] `SchemaMigrationTest`에 `purged_at` 검증 추가
- [x] `IntegrationTestBase`가 저장소 루트도 비우도록 (누적 문제 + 스윕 테스트 오염 방지)

### 검증

> 건수는 여기 적지 않습니다. **`docs/00-requirements-traceability.md`의 「검증 총계」가
> 유일한 출처**입니다 — 이 파일에 적었던 179·19·6·6·9가 **전부 stale이었습니다**(리뷰 지적).
> stale해지는 것은 건수이지 통과/실패 판정이 아니므로, 판정만 남깁니다.

- [x] `./gradlew test` 전부 통과 / 실패 0
- [x] `scripts/verify.sh`, `ui-verify.mjs` 회귀 없음
- [x] 실제 서버로 수동 확인 — 쿼터 8KB에서 5KB 두 번째 업로드가 507, 정리 작업이
      만료 파일 1건과 고아 2건을 지운 뒤 같은 업로드가 200으로 성공

### 결과

**테스트가 실제 버그를 하나 잡았다.** `runMaintenance()`가 `purgeExpired()`를 내부
호출하는데 자기 호출은 프록시를 거치지 않아 `@Transactional`이 적용되지 않았다.
직접 호출하는 테스트만 통과하고 **스케줄로 도는 실제 경로는 매일 밤 실패하는**
형태였다. `TransactionTemplate`으로 DB 쓰기만 감쌌고, 파일 삭제는 트랜잭션 밖에
둔다 — 롤백할 수 없는 작업이고 파일 I/O 동안 커넥션을 쥐면 풀이 마른다.
`tasks/lessons.md` 7번에 기록.

쿼터가 "벽"이 아니라 "수위"가 되는 것까지 확인했다. 거부 → 정리 → 같은 업로드 성공.

---

## 리뷰 대응 — 정확성 4건

구현 결함은 없었다. **네 건 모두 코드가 하는 일과 코드에 대해 적어 둔 말이 다른
경우**였고, 셋은 내가 쓴 문장이 틀린 것이었다.

- [x] `purgedAt`의 null 의미 — "파일이 디스크에 있다"는 정상 경로에서만 참.
      삭제와 표시 사이에 실패하면 null인 채 파일이 없다. 순서는 그대로 두고
      서술을 "아직 purge가 기록되지 않음"으로 고침. `03-api.md` 예시에도 추가
- [x] 혼합 거부의 422 설명 — `a.exe`(정책) + `b.txt`(용량)는 422인데 문서는 422를
      "다시 보내도 결과는 같음"이라 했다. 세 곳(`UploadResponse`·컨트롤러·`03-api.md`)
      통일 + `mixedRejectionReasonsAreReportedAs422`로 고정
- [x] 507 브라우저 검증의 경합 — `#upload-results > .result:first-child`로 앵커
- [x] `cleanup-batch-size`의 범위 — 보존 정리에만 적용됨을 주석에 명시.
      고아 스윕 스트리밍은 인터페이스 변경이라 후속으로

### 검증

- [x] `./gradlew test` 전부 통과 / 실패 0 (혼합 배치 케이스 추가)
- [x] `scripts/verify.sh`, `ui-verify.mjs` 회귀 없음 — 브라우저는 2회 반복 동일
- [x] `GET /files` 응답에 `purgedAt: null`이 문서 예시대로 실리는지 기동한 서버로 확인
- [x] 507 경합을 **재현해서 확인** — 스텁에 1.5초 지연을 넣자 기존 셀렉터로는 실패하고
      앵커 셀렉터로는 전부 통과

### 결과

**코드를 읽고 "경합이 아니다"라고 판단한 것이 틀렸다.** 리뷰 답변에서 "4절의
`page.reload()`가 목록을 비우므로 그 경로는 막혀 있다"고 적었는데, reload를 빼고
돌려도 통과했다 — 응답이 즉시 와서 **경합에서 이기고 있었을 뿐이다.** 지연을 넣어야
드러났다. `tasks/lessons.md` 9번에 기록.

혼합 배치 테스트는 **수정 전에도 통과한다.** 이번엔 구현이 옳고 문서만 틀렸으므로
"고치기 전에 실패하는 것"이 아니라 "문서가 코드와 다른 것"이 증명 대상이었다.

---

## 리뷰 대응 — 과장된 보장 6건

**여섯 건 다 구현이 아니라 서술 문제였고, 두 건은 문장 안에서 앞뒤가 모순이었다.**

- [x] 경합 수식의 조건 해석이 뒤집힘 — `Q − U ≥ K`를 "천장에 붙었을 때"로 옮겼는데
      정반대. 실제 초과량 `max(0, N·min(R,K) − R)`을 구간별로 적고, `R = K`에서
      최대이며 `R = N·K`에서 0이 된다는 것까지. 표에 `R`과 상한 열을 나란히 넣어
      본문 식으로 검산되게 함
- [x] "실패하면 아무것도 남지 않는다" — `deleteQuietly`가 `IOException`을 삼키므로
      정리 자체가 실패할 수 있다. 네 층(구조적 / `ATOMIC_MOVE` 한정 / 시도 /
      스윕 회수)으로 분리
- [x] "합계 대상이 보존 창 안에 갇힌다" — 삭제 실패 행은 계속 잡힌다.
      `unpurgedBytesStillCountAgainstTheQuota`가 이미 반례였다
- [x] `todo.md`의 건수 전부 제거 — 179·19·6·6·9가 모두 stale이었다
- [x] `.gitignore` `/package.json`으로 루트 한정
- [x] 커서용 복합 인덱스는 한계만 기록하고 다음 PR로 (마이그레이션을 거기서 다룸)

### 검증

- [x] 초과량 식을 스크립트로 `R`에 대해 훑어 최댓값 위치와 0이 되는 지점을 확인.
      문서 표의 모든 행이 식과 일치
- [x] `git check-ignore -v`로 루트만 무시되고 `frontend/package.json`은 추적되는지 확인
- [x] `./gradlew test` 전부 통과 / 실패 0
- [x] `scripts/verify.sh`, `ui-verify.mjs` 회귀 없음

### 결과

**철회한 과장의 대체 문장이 또 과장이었다.** `min-free-space`를 낮추면서 그 자리에
놓은 "실제 보장은 쓰레기가 남지 않는다까지"가 바로 다음 지적을 받았다. 과장을
고치는 순간이 "이번엔 약하게 적었다"는 기분 때문에 가장 위험하다. `lessons.md`
8번에 그 항목을 덧붙이고, 10번(수식을 적었으면 표를 그 식으로 검산한다)을 추가했다.

지적 1은 **파이썬 다섯 줄로 `R`을 훑었으면 바로 나왔다.** 유도를 적어 놓고 자기
예시에 대입해 보지 않은 것이다.

---

## DB 권한 분리 실효화

**계정을 나눈 것과 분리한 것은 다르다.** 앱 컨테이너가 `SPRING_FLYWAY_PASSWORD`로
마이그레이터(`ALL PRIVILEGES`) 자격증명을 들고 있었으므로, 앱을 장악한 쪽은 자기
환경변수를 읽어 그 계정으로 붙으면 그만이었다. `grants.sql`이 막겠다고 적어둔 위협
모델이 정확히 그 경우다.

- [x] 마이그레이션을 `migrate` 프로파일의 one-shot 컨테이너로 분리.
      앱에서 `SPRING_FLYWAY_*` 제거, `SPRING_FLYWAY_ENABLED=false`
- [x] 종료를 데몬 스레드에 맡기지 않고 `System.exit(SpringApplication.exit(...))`로 명시
- [x] `grants`를 one-shot 서비스로 자동화. 매 배포마다 재적용
- [x] 순서가 바뀌어 임시 광범위 권한이 불필요해짐 — **앱 계정이 단 한 순간도
      `extguard.*` 전체 권한을 갖지 않음**
- [x] `01-users.sql` → `01-users.sh`. 비밀번호를 커밋된 파일에서 제거
- [x] V4 인덱스 — 커서 쿼리가 purge된 행을 헛읽지 않게 (PR #4에서 넘긴 후속)

### 계획에 없었는데 발견한 것

`grants.sql`이 `mysql-init/`에 있어 `/docker-entrypoint-initdb.d`로 마운트되고 있었다.
그 디렉터리는 **DB 최초 기동 시** 실행되는데 그때는 테이블이 없어 테이블 단위 GRANT가
불가능하다. README는 이 파일을 "마이그레이션 이후 수동 실행"이라고 안내하고 있었으니,
처음부터 그 자리에 있으면 안 되는 파일이었다. `deploy/grants.sql`로 옮겼다.

### 검증

- [x] **one-shot이 실제로 종료하는지 — 실패를 먼저 재현했다.** 수정 전 jar을 웹 서버
      없이 돌리면 45초 타임아웃까지 종료하지 않고(`exit=124`), 수정 후 `migrate`
      프로파일은 `exit=0`에 마이그레이션 3건 적용
- [x] `docker compose config`(데몬 불필요)로 의존 체인과 **앱 환경변수에 마이그레이터
      흔적 0건**, 두 컨테이너의 `DB_PASSWORD`가 서로 다름을 기계적으로 확인
- [x] `ALTER TABLE ... DROP INDEX`가 H2 MySQL 모드에서 도는지 **가정하지 않고 실행해 확인**
- [x] `gradlew test` 전부 통과 / 실패 0, `verify.sh`·`ui-verify.mjs` 회귀 없음

### 확인하지 못한 것

**이 환경에는 Docker 데몬이 없다**(CLI만 있고 `/var/run/docker.sock` 없음). 다음은
인스턴스에서 확인해야 한다.

- 3단계 부팅이 실제로 순서대로 도는지
- `01-users.sh`가 init 디렉터리에서 실행되는지 — MySQL 이미지가 `*.sh`를 실행한다는 것은
  **문서 기반이고 실행해 확인하지 못했다**
- `REVOKE IF EXISTS`가 MySQL 8.4에서 의도대로 동작하는지
- `SHOW GRANTS FOR 'extguard_app'@'%'` 결과
- MySQL 옵티마이저가 V4 인덱스를 선택하는지(`EXPLAIN`) — H2 테스트가 증명하는 것은
  마이그레이션이 그 인덱스를 만든다는 것까지다

---

## 다음

`CLAUDE.md`에 적힌 계획 작업은 이것으로 끝났다.

미결로 남긴 것: 고아 스윕이 이름 목록 전체를 메모리에 올린다. 작은 파일이 아주 많은
저장소에서만 문제가 되고, 고치려면 `FileStorage`가 `Stream`을 반환해야 한다.

> 이전에 "`StorageProperties.maxFileSize`가 어디서도 읽히지 않는다"고 적어 둔 것은
> **내가 확인하지 않고 쓴 거짓**이었다. `UploadValidator` R2에서 실제로 쓰고 있다.
