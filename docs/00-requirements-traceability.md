# 요구사항 추적표

요구사항 각 항목이 어디에 구현되고 무엇으로 검증되는지 대조표입니다.

경로 표기는 `backend/src/main/java/com/flow/extguard/` 이하를 생략합니다.
테스트 경로는 `backend/src/test/java/com/flow/extguard/` 이하를 생략합니다.

---

## A. 확장자 차단 정책 관리 화면

### 고정 확장자

| 요구사항 | 구현 | 검증 |
|---|---|---|
| bat, cmd, com, cpl, exe, scr, js 7개 | `policy/domain/FixedExtensions.java`<br>`db/migration/V2__seed_fixed_extensions.sql` | `SchemaMigrationTest#seededRowsMatchTheJavaConstant`<br>`PolicyApiIntegrationTest#returnsFixedExtensionsUnblockedByDefault` |
| default는 unCheck 상태 | `V2__seed...sql` (`blocked = FALSE`) | `SchemaMigrationTest#seedsExactlySevenFixedExtensionsAllUnblocked` |
| check/uncheck 시 DB 저장 | `policy/service/ExtensionPolicyService#setFixedBlocked`<br>`PATCH /api/v1/policy/extensions/fixed/{ext}` | `PolicyApiIntegrationTest#fixedToggleIsPersisted` |
| 새로고침 시 유지 | 클라이언트 캐시 없음, 항상 DB 조회 | `PolicyApiIntegrationTest#fixedToggleIsPersisted`<br>브라우저 검증 "새로고침 후에도 exe가 체크 상태" |
| 고정 확장자는 커스텀 영역에 표시되지 않음 | 테이블 분리 (`fixed_extension_state` / `custom_extension`) | `PolicyApiIntegrationTest#fixedExtensionsAreNotListedAsCustom` |

### 커스텀 확장자

| 요구사항 | 구현 | 검증 |
|---|---|---|
| 입력 최대 길이 20자 | `policy/service/ExtensionNormalizer` (정규화 후 검사)<br>`frontend/index.html` `maxlength=20` (UX) | `ExtensionNormalizerTest#rejectsOverlongExtension`<br>`PolicyApiIntegrationTest#rejectsOverlongExtension` |
| "추가" 클릭 시 DB 저장 후 목록 표시 | `ExtensionPolicyService#addCustom`<br>`POST /api/v1/policy/extensions/custom` | `PolicyApiIntegrationTest#addsAndRemovesCustomExtension`<br>브라우저 검증 "sh 태그가 목록에 표시" |
| 최대 200개 | `config/PolicyProperties#maxCustomExtensions` | `PolicyApiIntegrationTest#enforcesTheTwoHundredLimit` |
| X 클릭 시 DB에서 삭제 | `ExtensionPolicyService#removeCustom`<br>`DELETE /api/v1/policy/extensions/custom/{ext}` | `PolicyApiIntegrationTest#addsAndRemovesCustomExtension` |
| 중복 추가 방지 | UNIQUE 인덱스 + 정규화 + 사전 조회 | `PolicyApiIntegrationTest#rejectsDuplicates` (`sh`/`SH`/`.sh`/`  sh  ` 전부 409) |

---

## B. 실제 파일 업로드 처리

| 요구사항 | 구현 | 검증 |
|---|---|---|
| 정책이 실제 업로드에 적용 | `upload/service/FileUploadService#upload` → `ExtensionPolicyService#blockedExtensions()` 매 요청 조회 | **`UploadEnforcementIntegrationTest#policyChangeTakesEffectOnTheNextUpload`**<br>`verify.sh` 2절 [CORE]<br>브라우저 검증 3절 [CORE] |
| 차단 대상은 명확한 사유와 함께 거부 | `common/ApiErrorCode` (message) + `UploadValidator` (detail) | `UploadEnforcementIntegrationTest#blocksDoubleExtension`<br>브라우저 검증 "거부 사유 메시지가 exe를 명시" |
| 정상 파일은 업로드 성공 | `upload/service/LocalFileStorage#store` | `UploadEnforcementIntegrationTest#storesAcceptedFileUnderGeneratedName` |

---

## 기획 / 개발 고려사항

### 1. 검증 / 보안

| # | 항목 | 구현 | 검증 |
|---|---|---|---|
| 1-1 | 확장자 신뢰 가능성 / 매직 넘버 | `upload/validation/ContentSignatureDetector`<br>`UploadValidator` R5(위장 탐지)·R6 | `ContentSignatureDetectorTest`<br>`UploadValidatorTest#rejectsExecutableDisguisedAsImage`<br>`#acceptsHonestlyNamedExecutableWhenPolicyAllowsIt`<br>`#rejectsExecutableWithoutExtension` |
| 1-2 | 대소문자 / 이중 확장자 / `.tar.gz` | `ExtensionNormalizer` (Locale.ROOT)<br>`FilenameAnalyzer` (확장자 체인) | `FilenameAnalyzerTest#extractsFullExtensionChain`<br>`UploadValidatorTest#blocksRegardlessOfCase` |
| 1-3 | 확장자 없음 / `.env` / 긴 파일명 | `FilenameAnalyzer` | `FilenameAnalyzerTest` (`handlesDotfiles`, `rejectsOverlongFilename`, `measuresLengthInUtf8Bytes`) |
| 1-4 | 확장자 입력값 검증 (특수문자·공백·유니코드·점) | `ExtensionNormalizer` (NFKC + 정규식) | `ExtensionNormalizerTest` |
| 1-5 | 서버 사이드 검증 필요성 | 서버가 유일한 판정자, 클라이언트는 항상 전송 | `scripts/verify.sh` (curl로 클라이언트 완전 우회) |
| 1-6 | 크기 / 개수 제한 | `application.yml` multipart + `max-part-count`<br>`UploadValidator` R2 | `UploadValidatorTest#rejectsOversizedFile` |
| 1-7 | 원본 파일명 사용 위험 | `LocalFileStorage` (UUID 경로) | `UploadEnforcementIntegrationTest#storesAcceptedFileUnderGeneratedName`<br>`FilenameAnalyzerTest#stripsPathComponents` |
| 1-8 | MIME 스푸핑 | `UploadValidator` R7 (기록만) | `UploadValidatorTest#declaredMimeTypeDoesNotCauseRejection` |

### 2. 정책 / 데이터

| # | 항목 | 구현 | 검증 |
|---|---|---|---|
| 2-1 | 고정·커스텀 충돌 | `ExtensionPolicyService#addCustom` + `ck_custom_not_fixed` | `FixedExtensionIntegrityTest`<br>`PolicyApiIntegrationTest#rejectsFixedExtensionAsCustom` |
| 2-2 | 변경 이력 / 감사 | `policy/domain/PolicyAuditLog`<br>`GET /api/v1/policy/audit` | `PolicyApiIntegrationTest#writesAnAuditTrail`, `#skipsAuditWhenNothingChanged` |
| 2-3 | 200 / 20 제한 근거와 초과 UX | `PolicyProperties` + `frontend/src/policy.js` 카운터·비활성화 | `PolicyApiIntegrationTest#enforcesTheTwoHundredLimit`<br>브라우저 검증 "카운터가 1 / 200으로 갱신" |
| 2-4 | 대량 조회 성능·인덱스 | 쿼리 2회·최대 207행, PK/UNIQUE 인덱스 | `docs/01-decisions.md` 2-4 |

### 3. UX / 예외

| # | 항목 | 구현 | 검증 |
|---|---|---|---|
| 3-1 | 차단 메시지 (무엇이·왜) | `ApiErrorCode` + `UploadValidator` detail 3단 구성 | 브라우저 검증 "근거(detail)가 함께 표시됨" |
| 3-2 | 로딩 / 에러 / 네트워크 실패 | `frontend/src/api.js` (`NetworkError`/`ApiError` 분리)<br>스켈레톤·재시도 배너·오프라인 감지 | 브라우저 검증 "정상 로드 시 오류 배너가 보이지 않음" |
| 3-3 | 저장 실패 시 화면·DB 일관성 | 낙관적 UI + 롤백 (`policy.js#toggleFixed`)<br>파일→DB 순서 + 보상 삭제 (`FileUploadService`) | `UploadEnforcementIntegrationTest#rejectedUploadIsLoggedButNotStored` |
| 3-4 | 접근성 / 반응형 | `aria-live`, `aria-label`, 키보드 드롭존, Grid + 600px 브레이크포인트, 다크모드 | 브라우저 스크린샷 |

### 4. 운영

| # | 항목 | 구현 | 검증 |
|---|---|---|---|
| 4-1 | 새로고침 / 동시 편집 정합성 | UNIQUE 인덱스가 최종 보증, 모든 변경 응답이 최신 스냅샷 반환 | `PolicyApiIntegrationTest#rejectsDuplicates`, `#fixedToggleIsPersisted` |
| 4-2 | 로그 / 모니터링 | `policy_audit_log`, `upload_record`(승인·거부 모두), WARN/INFO 로그, `/actuator/health` | `docs/01-decisions.md` 4-2 |
| 4-3 | 저장소 고갈 방어 | 쿼터 10GB + 디스크 최소 여유 1GB, 보존 30일 정리, 고아·부분 파일 정리 (`StorageBudget`, `StorageMaintenanceService`, `LocalFileStorage`) | `StorageQuotaIntegrationTest`, `StorageMaintenanceIntegrationTest`, **`StorageMaintenanceFailureIntegrationTest`**(삭제 실패 시 재시도), `LocalFileStorageTest` |
| 4-4 | 향후 확장 (사용자별 정책 / 화이트리스트) | 확장 지점 문서화 | `docs/01-decisions.md` 4-4 |

---

## 검증 총계

> **이 표가 검증 건수의 유일한 출처입니다.** `README.md`·`02-tooling.md`·`04-deployment.md`는 숫자를 적지 않고 여기를 가리킵니다.
>
> 처음에는 같은 숫자를 네 문서에 복사해 뒀고, 그 결과 **세 번 어긋났습니다**(README가 131에 멈춤, 클래스별 개수 12/25/6이 전부 오차). 클래스별 개수 표기도 같은 이유로 제거하고 클래스명만 참조합니다.

| 계층 | 건수 | 실행 방법 |
|---|---|---|
| 백엔드 단위·통합 테스트 | **186** | `cd backend && ./gradlew test` |
| API 엔드투엔드 (curl) | **38** | `scripts/verify.sh` |
| 브라우저 (Playwright + Chromium) | **23** | `docs/04-deployment.md` 참조 |
