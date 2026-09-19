# Todo

현재 작업의 계획과 결과를 적는다. 완료된 작업은 PR 머지 후 지운다.

---

## 현재: 저장소 고갈 방어 (PR)

### 상한

- [x] `StorageProperties`에 `quota`(10GB), `min-free-space`(1GB), `retention`(30d),
      `orphan-grace-period`(24h), `cleanup-cron` 추가
- [x] `StorageBudget` — 요청당 한 번 측정하고 배치 안에서 차감. 쿼터와 디스크 여유를 각각 검사
- [x] 사용량은 디스크가 아니라 DB 합계로 계산 (`sumLiveBytes`). purged 행이 빠지므로
      합계 대상이 보존 창 안에 갇힘
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

- [x] `LocalFileStorageTest` 6건 — 실패한 쓰기가 아무것도 남기지 않음
- [x] `StorageQuotaIntegrationTest` 6건 — 507, 기록 남김, 배치가 같은 여유를 두 번 쓰지 못함
- [x] `StorageMaintenanceIntegrationTest` 9건 — 보존/고아/유예/멱등
- [x] `SchemaMigrationTest`에 `purged_at` 검증 추가
- [x] `IntegrationTestBase`가 저장소 루트도 비우도록 (누적 문제 + 스윕 테스트 오염 방지)

### 검증

- [x] `./gradlew test` 179건 통과 / 0 실패
- [x] `scripts/verify.sh` 38건, `ui-verify.mjs` 19건 회귀 없음
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

## 다음

**DB 권한 분리 실효화** — one-shot 마이그레이션 컨테이너, 앱 환경변수에서
`SPRING_FLYWAY_*` 제거. 이번 PR에서 `02-grants.sql`을 건드렸으므로 함께 검토할 것.

범위 밖으로 남긴 것: `StorageProperties.maxFileSize`가 선언만 되어 있고 어디서도
읽히지 않는다. 크기 제한은 실제로 서블릿 컨테이너가 한다.
