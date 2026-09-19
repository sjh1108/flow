# 사용한 스킬 / 플러그인 / 도구

실제로 사용한 것만 적었습니다. 사용하지 않은 항목은 목록에 없습니다.

## MCP 서버

| 도구 | 어디에 썼는지 |
|---|---|
| **Context7 MCP** (`resolve-library-id`, `query-docs`) | Spring Boot 4.1의 multipart 설정 속성과 기본값(1MB/10MB), `MultipartProperties` 패키지 위치, Boot 4의 autoconfigure 모듈 분리 구조 확인에 사용 — 기억에 의존했다면 `starter-web`/`starter-webmvc` 개명과 Jackson 3 패키지 이동을 놓쳐 빌드가 깨졌을 항목 |

> 세션에 연결된 다른 MCP 서버(GitHub, Vercel, Notion, Figma, Gmail 등)는 이 작업에 해당 사항이 없어 사용하지 않았습니다. Atlassian Rovo와 Canva는 인증이 필요한 상태로, 이 세션에서는 인증 플로우를 실행할 수 없습니다.

## 내장 도구

| 도구 | 어디에 썼는지 |
|---|---|
| **WebSearch** | OCI Always Free ARM 한도 축소(2026-06-15, 4 OCPU/24GB → 2 OCPU/12GB), Render·Railway 무료 티어 현황(무료 MySQL 부재, 15분 슬립), Spring Boot 3.5 EOL(2026-06-30) 및 4.1 지원 기한 확인 — 호스팅 권고와 프레임워크 버전 선택의 근거 |
| **AskUserQuestion** | 판단이 필요한 4건(백엔드 호스팅, 프론트 라이브러리, Spring Boot 버전, 인증·감사 범위)을 근거와 함께 질의 |
| **EnterPlanMode / ExitPlanMode** | 구현 전 설계안을 문서화하고 승인받는 절차. 이 단계에서 고정 확장자 무결성 지적이 나와 스키마 설계가 바뀜 |
| **TaskCreate / TaskUpdate** | 9단계 작업 추적 |
| **Bash / Read / Write / Edit / Glob / Grep** | 코드 작성, 빌드·테스트 실행, 로그 분석 |

## 스킬

**사용하지 않았습니다.** 세션에 사용 가능한 스킬 목록(`code-review`, `security-review`, `dataviz`, `docx`, `pdf` 등)이 있었으나, 이 작업의 산출물이 코드·테스트·마크다운 문서라 해당하는 것이 없었습니다.

> `security-review` 스킬은 검토했으나 쓰지 않았습니다. 이 프로젝트에서 보안 검증은 스킬로 사후 점검할 대상이 아니라 **요구사항 그 자체**(매직 넘버 검사, 경로 조작, 확장자 정규화)이므로, 설계 단계부터 `UploadValidator`의 규칙 순서와 `FixedExtensionIntegrityTest`로 직접 다뤘습니다.

## 라이브러리 / 프레임워크

| 도구 | 버전 | 어디에 썼는지 |
|---|---|---|
| **Spring Boot** | 4.1.1 | API 서버 전체. `starter-webmvc`, `starter-data-jpa`, `starter-validation`, `starter-actuator`, `starter-flyway` |
| **Java** | 21 (Temurin) | 언어 런타임 |
| **Gradle** | 8.14.3 | 빌드. BOM은 `platform("org.springframework.boot:spring-boot-dependencies")`로 직접 임포트 (Boot 4는 `io.spring.dependency-management` 플러그인 미사용) |
| **Flyway** | Boot 관리 버전 | 스키마 마이그레이션. MySQL·H2 양쪽에서 같은 DDL이 돌도록 이식 가능하게 작성 |
| **MySQL** | 8.4 | 운영 DB. CHECK 제약(8.0.16+)이 고정 확장자 방어의 한 층 |
| **H2** | 2.4 (MySQL 모드) | 테스트와 `dev` 프로파일. CHECK 제약을 실제로 강제하므로 무결성 테스트가 유효 |
| **JUnit 5 + AssertJ** | Boot 관리 버전 | 단위·통합 테스트 |
| **MockMvc** | `spring-boot-starter-webmvc-test` | HTTP 계층 통합 테스트 (Boot 4에서 별도 모듈로 분리됨) |
| **Playwright + Chromium** | 1.x / preinstalled | 실제 브라우저에서 프론트엔드 검증. 빈 오류 배너 버그는 이 단계의 스크린샷으로만 발견됨 |
| **Docker / Compose** | — | 배포 스택(app + MySQL + Caddy) |
| **Caddy** | 2 | 자동 TLS 리버스 프록시 (선택적 프로파일) |
| **curl** | — | `scripts/verify.sh`의 엔드투엔드 검증 |
| **python3 http.server** | — | 프론트엔드 로컬 정적 서빙(검증용) |

**프론트엔드 런타임 의존성: 0개.** 드래그앤드롭, XHR 진행률, 상태 관리를 직접 구현했습니다. 근거는 `docs/01-decisions.md`와 아래 요약을 참조하세요.

## 도구 선택에서 판단이 갈렸던 지점

| 항목 | 채택 | 대안 | 이유 |
|---|---|---|---|
| 업로드 라이브러리 | 없음 (바닐라) | Uppy, Dropzone | 클라이언트 검증은 어차피 신뢰 불가라 라이브러리가 주는 값이 작고, 필요한 기능(다중 파일 + 진행률)이 약 250줄. CDN·CSP·공급망 리스크 제거가 더 이득 |
| 통합 테스트 DB | H2 (MySQL 모드) | Testcontainers MySQL | 이 환경에 Docker 데몬이 없어 Testcontainers 사용 불가. H2도 CHECK 제약을 강제하므로 핵심 방어는 검증됨. 마이그레이션 DDL을 이식 가능하게 작성해 같은 파일이 양쪽에서 실행되도록 함. 실제 MySQL 검증은 `docker compose` 단계에서 수행 |
| 진행률 표시 | XMLHttpRequest | fetch | fetch에는 여전히 업로드 진행률 이벤트가 없음 |
| 빌드 도구 | Gradle | Maven | 둘 다 가능. Kotlin DSL의 타입 안정성과 Spring 생태계 기본값을 따름 |
