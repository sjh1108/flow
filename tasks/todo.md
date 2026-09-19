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

- [x] `./gradlew test` **190건 통과 / 0 실패** (혼합 배치 1건 추가)
- [x] `scripts/verify.sh` 38건, `ui-verify.mjs` 23건 — 2회 반복 동일
- [x] `GET /files` 응답에 `purgedAt: null`이 문서 예시대로 실리는지 기동한 서버로 확인
- [x] 507 경합을 **재현해서 확인** — 스텁에 1.5초 지연을 넣자 기존 셀렉터로 3건 실패,
      앵커 셀렉터로 23건 통과

### 결과

**코드를 읽고 "경합이 아니다"라고 판단한 것이 틀렸다.** 리뷰 답변에서 "4절의
`page.reload()`가 목록을 비우므로 그 경로는 막혀 있다"고 적었는데, reload를 빼고
돌려도 통과했다 — 응답이 즉시 와서 **경합에서 이기고 있었을 뿐이다.** 지연을 넣어야
드러났다. `tasks/lessons.md` 9번에 기록.

혼합 배치 테스트는 **수정 전에도 통과한다.** 이번엔 구현이 옳고 문서만 틀렸으므로
"고치기 전에 실패하는 것"이 아니라 "문서가 코드와 다른 것"이 증명 대상이었다.

---

## 다음

**DB 권한 분리 실효화** — one-shot 마이그레이션 컨테이너, 앱 환경변수에서
`SPRING_FLYWAY_*` 제거. 이번 PR에서 `02-grants.sql`을 건드렸으므로 함께 검토할 것.

미결로 남긴 것: 고아 스윕이 이름 목록 전체를 메모리에 올린다. 작은 파일이 아주 많은
저장소에서만 문제가 되고, 고치려면 `FileStorage`가 `Stream`을 반환해야 한다.

> 이전에 "`StorageProperties.maxFileSize`가 어디서도 읽히지 않는다"고 적어 둔 것은
> **내가 확인하지 않고 쓴 거짓**이었다. `UploadValidator` R2에서 실제로 쓰고 있다.
