# 판단 근거

이 문서는 세 부분입니다.

1. **사용자가 지적하여 바로잡은 항목** — 리뷰에서 나온 지적과 그로 인한 설계 변경
2. **AI가 스스로 정정한 항목** — 조사 과정에서 뒤집힌 초기 가정
3. **고려사항별 결론** — 요청에 포함된 기획/개발 고려사항 각각에 대한 판단

---

## 1. 사용자가 지적하여 바로잡은 항목

### #1 고정 확장자 무결성 — 단일 테이블 설계의 허점

**지적**
> "그럼 여기서 외부 인원이 DB에 접근 혹은 API로 고정 확장자를 삭제하거나 고정 확장자가 아닌 상태를 만들 수 있게 할 수 있는 걸까?"

**맞는 지적인 이유**

최초 계획은 고정/커스텀을 `type VARCHAR(10)` 컬럼으로 구분해 `blocked_extension` 한 테이블에 담는 것이었습니다. 통합 설계의 근거로 "커스텀에 `exe`를 넣는 충돌이 UNIQUE 제약으로 자연히 잡힌다"를 들었는데, 이 논리는 **중복 방지**만 설명할 뿐 **고정성 보장**은 전혀 설명하지 못합니다. 지적을 받고 다시 보니 실제 결함이 세 가지 있었습니다.

| # | 결함 | 결과 |
|---|---|---|
| ① | 행이 삭제되면 조회 결과에서 사라짐 | 정책 화면이 체크박스를 7개가 아니라 **6개**만 렌더 |
| ② | `type`을 `FIXED`→`CUSTOM`으로 UPDATE하면 삭제 가드 통과 | 고정 확장자가 커스텀처럼 삭제 가능 |
| ③ | 고정 행을 지키는 수단이 서비스 코드의 `if` 하나뿐 | 리팩터링 한 번에 조용히 사라짐 |

즉 **"고정성"이 스키마 불변식이 아니라 그냥 컬럼 값**이었습니다. 컬럼 값은 UPDATE 한 번에 바뀝니다.

**바뀐 설계 — 4중 방어**

테이블을 나누고, 보증을 코드가 아니라 구조와 제약에 위임했습니다.

```
fixed_extension_state   extension PK, blocked, updated_at
                        CHECK (extension IN ('bat','cmd','com','cpl','exe','scr','js'))

custom_extension        id PK, extension UNIQUE, created_at
                        CHECK (extension NOT IN ('bat','cmd','com','cpl','exe','scr','js'))
```

| 층 | 방어 | 막는 것 |
|---|---|---|
| 1 | **리포지토리 분리** — `CustomExtensionRepository`는 `custom_extension`만 다루고, `FixedExtensionStateRepository`는 Spring Data의 빈 `Repository` 마커를 상속해 `delete`/`deleteById`/`save`를 **아예 물려받지 않음**(UPDATE 쿼리 하나만 노출) | 애플리케이션 어디에도 고정 확장자를 지우는 코드 경로가 없음. 런타임 `if`가 아니라 타입 시스템이 막음 |
| 2 | **`ck_custom_not_fixed`** | DB에 직접 붙어 `INSERT INTO custom_extension VALUES('exe')`를 해도 거부 |
| 3 | **`ck_fixed_whitelist`** | 고정 테이블에 8번째 확장자가 생길 수 없음 |
| 4 | **읽기 경로가 코드 상수 기준** — `getPolicy()`가 DB를 훑는 게 아니라 `FixedExtensions.ALL`(7개 `List.of`)을 순회하며 DB 상태를 join | 누가 행을 지워도 화면은 7개를 `blocked=false`로 렌더. **고정 목록은 데이터가 아니라 코드** |

여기에 배포 계층에서 한 겹 더:

| 5 | **DB 권한 분리** (`deploy/mysql-init/02-grants.sql`) | 런타임 계정은 `fixed_extension_state`에 `SELECT, UPDATE`만 보유. `INSERT`/`DELETE` 없음. 애플리케이션이 탈취되어 임의 SQL을 실행하더라도 고정 행을 지우거나 만들 수 없음 |

API 표면에서도 **고정 확장자 삭제 엔드포인트를 만들지 않았습니다.** 토글(`PATCH`)만 존재합니다.

**이를 고정하는 테스트** — `backend/src/test/java/.../integration/FixedExtensionIntegrityTest.java`

| 테스트 | 검증 |
|---|---|
| `customDeleteEndpointCannotRemoveAFixedExtension` | `DELETE .../custom/exe` → 404, 고정 7행 불변 |
| `thereIsNoFixedDeleteEndpoint` | `DELETE .../fixed/exe` → 405 |
| `databaseConstraintBlocksFixedExtensionAsCustom` | 7개 각각 raw SQL 삽입 시도 → 전부 제약 위반 |
| `databaseConstraintBlocksNewFixedExtension` | 고정 테이블에 `sh` 삽입 → 제약 위반 |
| `policyStillReportsSevenFixedExtensionsAfterRowDeletion` | raw SQL로 `exe` 행 삭제 후 조회 → **여전히 7개** |
| `javaConstantMatchesDatabaseConstraint` | Java 상수와 DB 시드 일치 |

> **남은 한계.** DB root 권한을 가진 사람은 CHECK 제약 자체를 `ALTER TABLE ... DROP CONSTRAINT`로 제거할 수 있습니다. 이건 방어 불가능하며(그 권한이면 무엇이든 가능), 따라서 대응은 기술적 차단이 아니라 root 자격증명 관리와 감사 영역입니다. 방어 목표는 "**애플리케이션 계정 수준의 침해로는 고정성이 깨지지 않는다**"까지입니다.

---

### #2 문서 구성 — 사용자 발견 사항을 앞에 배치

**지적**: "AI가 놓쳤거나 틀린 항목 — 내가 발견한 걸 강조하는 방향"

원래는 AI 자체 정정 목록만 있었습니다. 리뷰에서 나온 지적이 실제로 설계를 바꿨으므로, 그 기록이 문서의 중심이어야 맞습니다. 1절을 사용자 지적으로 바꾸고 AI 자체 정정은 2절 참고로 내렸습니다.

---

## 2. AI가 스스로 정정한 항목

조사 과정에서 초기 가정이 틀린 것으로 드러난 것들입니다. 기록을 남기는 이유는, 이 중 일부는 **검증하지 않았다면 그대로 잘못된 산출물이 되었을** 항목이기 때문입니다.

| # | 초기 가정 | 실제 | 검증 수단 | 검증 안 했다면 |
|---|---|---|---|---|
| 1 | "Spring Boot 3.5가 안정적인 선택" | 3.5는 **2026-06-30 OSS EOL**, 3.x 전 브랜치 보안 패치 없음 | WebSearch | EOL 프레임워크로 신규 프로젝트 시작 |
| 2 | "OCI Always Free = 4 OCPU / 24GB" | **2026-06-15자로 2 OCPU / 12GB로 반감** (공지 없이) | WebSearch | 존재하지 않는 여유 용량 전제로 배포 권고 |
| 3 | "Render 무료 티어로 MySQL 가능" | Render 무료 DB는 **Postgres 전용·90일 만료**, Railway는 30일 트라이얼뿐 | WebSearch | 무료 MySQL을 전제로 한 잘못된 호스팅 권고 |
| 4 | `toLowerCase()`를 로케일 없이 사용 | 터키어 로케일에서 `"TIF".toLowerCase()` → `"tıf"`(점 없는 i)라 차단 목록과 매칭 실패 | 직접 검토 → 테스트로 고정 | 서버 로케일에 따라 차단이 조용히 뚫림 |
| 5 | Boot 4도 `spring-boot-starter-web` | Boot 4는 **`spring-boot-starter-webmvc`**, Jackson 3(`tools.jackson`), autoconfigure 모듈 분리 | Context7 MCP + WebSearch | 빌드 실패 |
| 6 | `io.spring.dependency-management` 플러그인으로 BOM 적용 | Boot 4에서 동작하지 않아 모든 의존성 버전이 비어 해석 실패 | 빌드 실행 | 빌드 실패 |
| 7 | `@AutoConfigureMockMvc`가 `...test.autoconfigure.web.servlet`에 존재 | Boot 4에서 **`org.springframework.boot.webmvc.test.autoconfigure`**로 이동(별도 `spring-boot-starter-webmvc-test` 모듈) | 테스트 컴파일 실패 → jar 내용 직접 확인 | 테스트 컴파일 불가 |
| 8 | `HttpStatus.UNPROCESSABLE_ENTITY` / `PAYLOAD_TOO_LARGE` | Spring 7에서 deprecated. RFC 9110 명칭 **`UNPROCESSABLE_CONTENT` / `CONTENT_TOO_LARGE`** | 컴파일 경고 | 동작은 하나 deprecated API 사용 |

### 구현 중 실행으로 발견한 결함

테스트와 실행이 아니었으면 남았을 **실제 버그** 3건입니다.

| # | 결함 | 발견 경로 | 조치 |
|---|---|---|---|
| 1 | 파일 파트 없이 업로드 요청 시 **500** 반환 | 통합 테스트 `rejectsRequestWithNoFilePart` | `files`를 바인딩 단계에서 optional로 바꿔 서비스의 `NO_FILE_SUBMITTED`(400)가 처리하도록 수정 |
| 2 | 지원하지 않는 메서드/알 수 없는 경로가 전부 **500** | `verify.sh`가 405를 기대했는데 500 관측 | catch-all 핸들러가 Spring MVC의 `ErrorResponse` 4xx를 가로채 500으로 만들고 있었음. 4xx는 그대로 보고하도록 수정 + `ErrorHandlingIntegrationTest` 추가 |
| 3 | 정상 로드인데 **빈 빨간 오류 배너**가 화면에 표시 | 브라우저 스크린샷 육안 확인 | `.banner { display: flex }`가 `hidden` 속성(UA의 `display:none`)을 이겨서 발생. `[hidden] { display: none !important }` 추가 + 브라우저 테스트에 배너 가시성 단언 추가 |

> 3번은 단위·통합 테스트가 모두 통과하는 상태에서 **스크린샷으로만** 드러났습니다. 렌더링 결과를 눈으로 확인하는 절차가 왜 필요한지 보여주는 사례라 기록합니다.

---

## 3. 고려사항별 결론

### 3.1 검증 / 보안 관점

#### 1-1. 확장자만 믿어도 되는가

**결론: 믿을 수 없다. 내용 시그니처를 함께 본다.**

`ContentSignatureDetector`가 선두 512바이트만 읽어 매직 넘버를 대조합니다. PE(`MZ`), ELF, Mach-O, Java class, shebang(`#!`), ZIP/RAR/7z/GZIP, PDF, PNG/JPEG/GIF/BMP/ICO.

- 시그니처가 **실행파일·스크립트 계열**이면 확장자와 무관하게 거부 (`EXECUTABLE_CONTENT`)
- 확장자가 신뢰 가능한 단일 시그니처를 갖는 형식(png/jpg/gif/bmp/pdf/zip/gz)인데 내용이 다르면 거부 (`CONTENT_TYPE_MISMATCH`)
- `docx`, `txt`, `csv`처럼 시그니처가 불안정하거나 없는 형식은 **교차검증하지 않음** — 신뢰성 없는 규칙은 오탐만 만듦

폴리글롯 대응: 이미지 확장자를 자칭하는 파일 선두에 `<?php`, `<script`, `<%@`, `<%=`가 있으면 웹셸로 판정. 정상 `.html` 업로드는 영향 없도록 이미지 확장자일 때만 적용.

- 구현: `upload/validation/ContentSignatureDetector.java`, `UploadValidator.java` (R5, R5b, R6)
- 테스트: `ContentSignatureDetectorTest`, `UploadValidatorTest#rejectsExecutableDisguisedAsImage`
- **한계**: 512바이트 스캔은 시그니처 판별용이며 바이러스 스캔이 아닙니다. 실제 운영에서는 ClamAV 등 연동이 별도로 필요합니다(범위 외). `MZ`는 2바이트라 "MZ"로 시작하는 텍스트 파일이 오탐될 수 있으나, PE 판별의 표준 방식이고 확률이 극히 낮아 수용했습니다.

#### 1-2. 대소문자 / 이중 확장자

**결론: 전부 정규화하고, 확장자 체인 전체를 검사한다.**

- **대소문자**: NFKC 정규화 → `toLowerCase(Locale.ROOT)`. `.EXE`, `.Exe` 모두 `exe`
- **이중 확장자**: `invoice.pdf.exe` → 체인 `[pdf, exe]`. **모든 세그먼트**를 차단 목록과 대조

체인 전체를 보는 이유는 Windows의 확장자 숨김에서 `invoice.pdf.exe`가 `invoice.pdf`로 보이는 고전적 우회이기 때문입니다. 마지막 세그먼트만 보면 이 방향을, 첫 세그먼트만 보면 반대 방향(`file.exe.txt`)을 놓칩니다. 검사 비용은 0에 가깝습니다.

- **`.tar.gz`**: 체인 `[tar, gz]`로 둘 다 검사
- **한계와 완화책**: `backup.exe.log` 같은 정상 파일이 걸리는 오탐이 있습니다. `extguard.policy.check-full-extension-chain=false`로 마지막 세그먼트만 검사하도록 완화 가능하며, 이 경우 이중 확장자 우회가 다시 열린다는 점을 명시합니다.

#### 1-3. 확장자 없는 파일 / 점으로 시작 / 매우 긴 파일명

| 케이스 | 처리 |
|---|---|
| `README` (확장자 없음) | 체인 비어 있음 → 확장자 규칙 미적용, 내용 검사는 수행 |
| `.env` (점 시작) | 베이스명 없음, 체인 `[env]`로 취급하여 **차단 대상에 포함 가능** |
| 매우 긴 파일명 | **UTF-8 255바이트** 제한. 한글은 자당 3바이트이므로 문자 수가 아니라 바이트로 측정 |
| `evil.exe.` (후행 점) | Windows가 후행 점을 조용히 제거하므로 **먼저 제거한 뒤** 검사 → `exe`로 차단 |
| `CON.txt` | Windows 예약 장치명 거부 |
| `../../etc/passwd` | 경로 성분 제거 → `passwd` |

- 구현: `upload/validation/FilenameAnalyzer.java`
- 테스트: `FilenameAnalyzerTest` (17케이스)

#### 1-4. 확장자 입력값 검증

**결론: 단일 정규화 지점을 두고 `[a-z0-9]{1,20}`만 허용한다.**

`ExtensionNormalizer`가 유일한 진입점입니다.

1. NFKC 정규화 — 전각 `ＥＸＥ` → `exe` (동형 문자 우회 차단)
2. 앞뒤 공백 제거
3. 선행 점 제거 — `.exe` → `exe` (사용자가 자연스럽게 쓰는 형태 수용)
4. `toLowerCase(Locale.ROOT)`
5. 사유별 진단: 점 포함 / 공백 포함 / 20자 초과 / 그 외 문자셋 위반

길이 제한은 **정규화 후** 값에 적용합니다. 클라이언트의 `maxlength=20`은 UX일 뿐입니다.

- 구현: `policy/service/ExtensionNormalizer.java`
- 테스트: `ExtensionNormalizerTest` (25케이스, 터키어 로케일 포함)

#### 1-5. 서버 사이드 검증의 필요성

**결론: 서버가 유일한 방어선. 클라이언트 검증은 UX 전용.**

이 원칙을 문서로만 주장하지 않고 **구조로 드러냈습니다**: 프론트엔드는 차단 대상임을 이미 알고 있어도 **요청을 막지 않고 항상 서버로 전송**하며, 화면에 뜨는 판정은 언제나 서버 응답입니다. 클라이언트가 막아버리면 "서버가 강제한다"는 사실이 증명되지 않기 때문입니다.

`scripts/verify.sh`는 브라우저를 거치지 않고 `curl`로 직접 API를 때려 동일한 차단을 확인합니다 — 클라이언트를 완전히 우회해도 막힌다는 증거입니다.

#### 1-6. 업로드 파일 크기 / 개수 제한

| 항목 | 값 | 강제 지점 |
|---|---|---|
| 파일당 크기 | 20MB | 서블릿 컨테이너(`spring.servlet.multipart.max-file-size`) + `UploadValidator` R2 |
| 요청 전체 | 210MB | `max-request-size` |
| 요청당 파일 수 | 10 | `UploadValidator` + `server.tomcat.max-part-count=12` |

컨테이너가 컨트롤러 진입 **전에** 거부하므로 `MaxUploadSizeExceededException`을 `GlobalExceptionHandler`에서 동일한 JSON 형태로 매핑했습니다. 이게 없으면 클라이언트가 HTML 오류 페이지를 받아 파싱에 실패합니다.

#### 1-7. 원본 파일명 사용 위험

**결론: 원본 파일명을 경로 구성에 일절 쓰지 않는다.**

- 디스크상 이름은 `UUID.bin`, 경로는 `yyyy/MM/dd/` 분산
- 원본명은 DB 컬럼(`original_filename`, `display_filename`)에만 보관
- 경로에 사용자 입력이 **한 글자도 들어가지 않으므로** 경로 조작은 필터링 대상이 아니라 구조적으로 불가능
- 퍼미션 0600(파일) / 0700(디렉터리), 저장 루트는 웹 루트 밖
- **다운로드 엔드포인트를 만들지 않음** — 사용자 파일을 브라우저로 되돌려주는 것이 실제 위험 지점(`text/html` 반사 XSS 등)이라 의도적으로 범위에서 제외

#### 1-8. MIME 타입 스푸핑

**결론: 신뢰하지 않으므로 판단 근거로도 쓰지 않는다. 기록만 한다.**

브라우저가 보내는 `Content-Type`은 ① 공격자가 임의로 설정 가능하고 ② 정상 파일에서도 OS별 MIME DB 차이로 자주 틀립니다. 이걸 거부 근거로 삼으면 **보안은 늘지 않고 오탐만 늘어납니다**. 실제 위협(내용이 실행파일)은 R5/R6가 이미 잡습니다.

따라서 선언 MIME과 탐지 시그니처가 불일치하면 **로그만 남기고 통과**시킵니다(R7). DB의 `declared_content_type` 컬럼에 기록되어 사후 분석은 가능합니다.

- 테스트: `UploadValidatorTest#declaredMimeTypeDoesNotCauseRejection` — 실제 PNG에 `application/x-msdownload`를 붙여도 통과

---

### 3.2 정책 / 데이터 관점

#### 2-1. 고정 확장자와 커스텀이 겹칠 때

**결론: 3중으로 막고, 사용자에게는 갈 곳을 알려준다.**

1. 서비스에서 `FixedExtensions.contains()` 확인 → `409 EXT_IS_FIXED`
2. 메시지: *"'exe'는 고정 확장자입니다. 위 고정 확장자 영역에서 체크해 주세요."* — 막기만 하지 않고 대안 제시
3. DB `ck_custom_not_fixed` 제약 — 애플리케이션을 우회해도 차단
4. 프론트엔드가 입력 즉시 동일 안내 (UX)

#### 2-2. 정책 변경 이력 / 감사

**결론: 필요하다. 다만 이 요구사항 범위에서는 "사람"이 아니라 "변경"을 기록한다.**

`policy_audit_log`에 `action`, `extension`, `extension_type`, `before_value`, `after_value`, `actor`, `actor_ip`, `user_agent`, `created_at`을 남깁니다.

로그인이 범위에 없으므로 `actor`는 신원이 아니라 **인가 방식**(`admin-token` / `anonymous`)을 기록합니다. 실질적 추적은 IP와 User-Agent가 담당합니다. 정식 사용자별 추적이 필요해지면 `actor` 컬럼에 사용자 ID를 넣는 것으로 자연스럽게 확장됩니다.

무의미한 기록은 남기지 않습니다 — 값이 바뀌지 않은 토글은 감사 로그를 쓰지 않습니다(`skipsAuditWhenNothingChanged` 테스트).

#### 2-3. 200개 / 20자 제한의 근거와 초과 시 UX

**근거**

- **20자**: 실존하는 최장 확장자가 10자 내외(`properties`, `configuration`)라 20자면 충분히 여유롭고, `VARCHAR(20)` 인덱스 크기도 작게 유지됩니다.
- **200개**: 업로드 1건당 확장자 체인(보통 1~2개)을 해시셋과 대조하므로 200개든 2000개든 성능 차이는 없습니다. 실제 이유는 **성능이 아니라 정책 관리 가능성** — 사람이 검토할 수 없는 크기의 차단 목록은 관리되지 않고, 화이트리스트 방식으로 전환해야 한다는 신호입니다.

**초과 시 UX**

- 카운터가 `198 / 200`에서 주황, `200 / 200`에서 빨강
- 한도 도달 시 입력창과 버튼이 **비활성화**되고 placeholder가 "최대 200개에 도달했습니다"로 변경
- 서버는 `409 CUSTOM_LIMIT_EXCEEDED` + *"사용하지 않는 항목을 삭제한 뒤 다시 시도해 주세요"* — 막기만 하지 않고 다음 행동 제시

#### 2-4. 대량 조회 / 저장 시 성능·인덱스

- 차단 목록 조회는 쿼리 2회(고정 중 blocked / 커스텀 전체), 최대 **207행**. 인덱스 불필요한 규모지만 `custom_extension.extension`은 UNIQUE(따라서 인덱스), `fixed_extension_state.extension`은 PK입니다.
- 업로드 1건당 정책 조회 1회. 요청당 1회만 읽고 배치 내 모든 파일에 적용해 N+1을 피합니다.
- 캐시는 **의도적으로 넣지 않았습니다**. 207행 조회는 캐시 무효화 로직의 복잡도와 "체크 직후 업로드에 즉시 반영" 요구 사이에서 얻을 게 없습니다. 정책 변경이 즉시 반영되는 것이 이 시스템의 핵심 요구입니다.

---

### 3.3 UX / 예외 관점

#### 3-1. 차단 시 보여줄 메시지

**결론: 무엇이·왜 막혔는지를 3단으로 제시한다.**

| 층 | 예시 | 용도 |
|---|---|---|
| `message` | `'exe' 확장자는 차단되어 있어 업로드할 수 없습니다.` | 화면 헤드라인 |
| `detail` | `파일명 'invoice.pdf.exe'의 확장자 체인 [pdf,exe] 중 'exe'가 고정 차단 목록에 있습니다.` | 근거 — 왜 이 판정인지 |
| `code` | `EXTENSION_BLOCKED` | 문의·디버깅용 안정 식별자 |

`detail`이 중요한 이유: `invoice.pdf.exe`를 올린 사용자는 "pdf인데 왜 막히지?"라고 생각합니다. 체인을 보여줘야 납득됩니다. 고정/커스텀 출처도 명시해 어디서 해제할지 알 수 있게 했습니다.

#### 3-2. 로딩 / 에러 / 네트워크 실패

- **첫 로드**: 스켈레톤 애니메이션 (`prefers-reduced-motion` 존중)
- **로드 실패**: 인라인 배너 + "다시 시도" 버튼 (토스트로 흘려보내지 않음 — 복구 행동이 필요하므로)
- **네트워크 실패와 HTTP 오류 구분**: `NetworkError`와 `ApiError`를 별도 타입으로 둠. "서버에 연결할 수 없습니다"와 "확장자가 중복입니다"는 다른 UI가 필요
- **오프라인 감지**: `offline`/`online` 이벤트로 안내 및 자동 재조회
- **401**: "관리자 토큰이 필요합니다. 우측 상단에 토큰을 입력해 주세요" — 구체적 행동 지시

#### 3-3. 저장 실패 시 화면 상태와 DB 상태의 일관성

**화면 ↔ DB**: 체크박스는 **낙관적 UI + 실패 시 롤백**입니다. 즉시 반영해 반응성을 확보하되, 서버가 거부하면 체크 상태를 되돌리고 사유를 토스트로 알립니다. 롤백이 없으면 화면이 DB와 조용히 어긋납니다.

**파일 ↔ DB**: 순서가 `파일 기록 → DB insert → 실패 시 파일 삭제(보상)`입니다. 최악의 경우 **고아 파일**만 남고, "DB 행은 있는데 파일이 없는" 반대 방향은 발생하지 않습니다. 안전한 쪽으로 실패하도록 설계했습니다.
거부된 업로드는 디스크를 아예 건드리지 않고 DB 행만 남깁니다.
**한계**: 파일 기록과 DB insert 사이에 프로세스가 죽으면 고아 파일이 남습니다. 정리 잡은 범위 외이며, `upload_record`와 디스크를 대조하는 배치로 해결 가능합니다.

#### 3-4. 접근성 / 반응형

- 토스트 영역에 `aria-live="polite"`, 삭제 버튼에 `aria-label="sh 삭제"`
- 모든 입력에 `<label>` 연결, 드롭존은 `tabindex`/`role`/키보드(Enter·Space) 지원
- `:focus-visible` 아웃라인 유지
- CSS Grid + 단일 브레이크포인트(600px), 다크모드는 `prefers-color-scheme`로 자동
- `prefers-reduced-motion`에서 애니메이션 비활성화

---

### 3.4 운영 관점

#### 4-1. 새로고침 / 동시 편집 시 정합성

- **새로고침**: 모든 상태가 DB에 있고 클라이언트 캐시가 없으므로 F5 후 그대로 유지 (`fixedToggleIsPersisted` 테스트)
- **동시 추가**: 중복 방지의 실제 보증은 **UNIQUE 인덱스**입니다. 사전 조회는 메시지 품질용이며, 경쟁에서 진 쪽은 `DataIntegrityViolationException` → `409`로 처리됩니다.
- **모든 변경 응답이 최신 정책 전체를 반환**합니다. 클라이언트가 로컬 상태를 추측해 조립하지 않고 서버 스냅샷으로 교체하므로, 다른 사람이 바꾼 내용이 다음 조작 시 자동 반영됩니다.
- **한계**: 200개 한도는 경쟁 조건에서 아주 짧은 순간 초과 가능합니다(count 확인과 insert 사이). 초과분이 1~2개이고 실질 피해가 없어 수용했습니다. 엄밀히 막으려면 카운터 행에 `SELECT ... FOR UPDATE`가 필요한데, 모든 추가 요청을 직렬화하는 대가가 이득보다 큽니다.

#### 4-2. 로그 / 모니터링

| 대상 | 남기는 것 |
|---|---|
| 정책 변경 | `policy_audit_log` 테이블 + INFO 로그 (action, extension, actor, IP) |
| 업로드 | `upload_record` 테이블에 **승인·거부 모두** — 거부야말로 기록할 가치가 있는 사건 |
| 거부 상세 | `rejection_code`, `rejection_detail`, `detected_signature` — 어떤 우회가 시도되는지 분석 가능 |
| 인증 실패 | WARN + 메서드·경로·IP |
| MIME 불일치 | INFO (거부하지 않지만 추세 관찰용) |
| 미설정 경고 | 토큰 없이 기동 시 시작 시점 WARN |
| 헬스체크 | `/actuator/health` |

응답 본문에는 내부 정보를 넣지 않습니다. 예기치 못한 예외는 전체 스택을 서버 로그에만 남기고 클라이언트에는 일반 메시지만 보냅니다.

무결성 경보: `fixed_extension_state` 행 수가 7이 아니면 조회 시마다 WARN을 남깁니다.

#### 4-3. 향후 확장

- **사용자별 정책**: `fixed_extension_state`/`custom_extension`에 `owner_id`를 추가하고 UNIQUE를 `(owner_id, extension)`으로 변경. 감사 로그의 `actor`는 이미 사용자 ID를 받을 수 있는 형태.
- **화이트리스트 전환**: `UploadValidator`의 R4만 "체인이 허용 목록에 포함되는가"로 뒤집으면 됩니다. 정책 저장 구조는 그대로 재사용 가능. 커스텀 200개 한도에 도달하는 것이 전환을 검토할 신호입니다.
- **바이러스 스캔**: `UploadValidator` 뒤에 R8로 추가하는 자리가 이미 열려 있습니다(파일 저장 전).
- **다운로드**: 추가한다면 `Content-Disposition: attachment` + `X-Content-Type-Options: nosniff` + 별도 도메인이 전제 조건입니다.
