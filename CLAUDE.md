# flow — 파일 확장자 차단 시스템

Spring Boot 4.1 / Java 21 / MySQL 8.4 / 바닐라 JS(무의존).

요구사항은 두 개이고 **둘 다 있어야 완성**이다.

- **A. 정책 관리 화면** — 고정 확장자 7개(bat, cmd, com, cpl, exe, js, scr) 체크박스 +
  커스텀 확장자 최대 200개(각 20자 이내) 추가·삭제
- **B. 실제 업로드 강제** — A에서 설정한 정책이 진짜 업로드에서 강제될 것.
  A만 있고 B가 없으면 미완성이다.

작업 시작 전 순서대로 읽을 것:

1. `docs/01-decisions.md` — 모든 설계 근거와 **지금까지 지적받아 바로잡은 오류**
2. `docs/00-requirements-traceability.md` — 요구사항 ↔ 코드 ↔ 테스트 매핑, 검증 총계
3. `docs/03-api.md`, `docs/04-deployment.md` — 필요할 때

## Workflow Orchestration

### 1. Plan Mode Default
- Enter plan mode for ANY non-trivial task (3+ steps or architectural decisions)
- If something goes sideways, STOP and re-plan immediately - don't keep pushing
- Use plan mode for verification steps, not just building
- Write detailed specs upfront to reduce ambiguity

### 2. Subagent Strategy
- Use subagents liberally to keep main context window clean
- Offload research, exploration, and parallel analysis to subagents
- For complex problems, throw more compute at it via subagents
- One tack per subagent for focused execution

### 3. Self-Improvement Loop
- After ANY correction from the user: update `tasks/lessons.md` with the pattern
- Write rules for yourself that prevent the same mistake
- Ruthlessly iterate on these lessons until mistake rate drops
- Review lessons at session start for relevant project

### 4. Verification Before Done
- Never mark a task complete without proving it works
- Diff behavior between main and your changes when relevant
- Ask yourself: "Would a staff engineer approve this?"
- Run tests, check logs, demonstrate correctness

### 5. Demand Elegance (Balanced)
- For non-trivial changes: pause and ask "is there a more elegant way?"
- If a fix feels hacky: "Knowing everything I know now, implement the elegant solution"
- Skip this for simple, obvious fixes - don't over-engineer
- Challenge your own work before presenting it

### 6. Autonomous Bug Fixing
- When given a bug report: just fix it. Don't ask for hand-holding
- Point at logs, errors, failing tests - then resolve them
- Zero context switching required from the user
- Go fix failing CI tests without being told how

## Task Management

1. **Plan First**: Write plan to `tasks/todo.md` with checkable items
2. **Verify Plan**: Check in before starting implementation
3. **Track Progress**: Mark items complete as you go
4. **Explain Changes**: High-level summary at each step
5. **Document Results**: Add review section to `tasks/todo.md`
6. **Capture Lessons**: Update `tasks/lessons.md` after corrections

## Core Principles

- **Simplicity First**: Make every change as simple as possible. Impact minimal code.
- **No Laziness**: Find root causes. No temporary fixes. Senior developer standards.
- **Minimal Impact**: Changes should only touch what's necessary. Avoid introducing bugs.

## 작업 규칙

- 브랜치: claude/<작업명>, main에 직접 커밋 금지
- 각 작업이 끝나면 PR을 올리고 검토받은 뒤, accept된 다음에 다음 작업 진행
- 이번 PR 범위가 아닌 발견은 코멘트로 남기고 다음 PR에서 처리
- PR 본문에 검증 결과를 적을 때 실제 실행한 것만 적을 것

## 환경 함정 (실제로 겪은 것들)

- Spring Boot 4.1: io.spring.dependency-management 안 됨 → platform() BOM 명시
  spring-boot-starter-web → -webmvc, @AutoConfigureMockMvc는 webmvc-test 모듈
- verify.sh는 실행 중인 API가 필요. 8080 점유 프로세스를 반드시 먼저 정리할 것
  (구 jar가 포트를 쥐고 있으면 새 인스턴스가 바인딩에 실패해 옛 코드를 검증하게 됨)
- ui-verify.mjs: npm install playwright 후
  CHROMIUM=/opt/pw-browsers/chromium node scripts/ui-verify.mjs
  (설치되는 playwright 버전과 미리 깔린 chromium 리비전이 다름)
- 프론트엔드는 frontend/에서 python3 -m http.server 8081

## 검증

세 계층을 전부 돌려야 "통과"라고 말할 수 있다. CI는 앞의 두 개를 자동으로 돌린다.
**브라우저 계층은 CI에 없다** — Chromium이 필요해 의도적으로 뺐다(`ci.yml` 머리말).
그러므로 "CI 초록"은 브라우저 계층이 돌았다는 뜻이 아니고, 손으로 돌려야 한다.

서버는 **관리자 가드를 켠 채로** 띄운다. 끄면 정책 쓰기가 무인증이라 운영과 다른 모양이
되고, 권한 관련 동작을 잴 수 없다.

```bash
cd backend && EXTGUARD_ADMIN_TOKEN=test-secret \
  ./gradlew bootRun --args='--spring.profiles.active=dev'

# 아래 세 줄은 저장소 루트에서. cd를 subshell에 가둬야 뒤의 둘이 경로를 찾는다.
(cd backend && ./gradlew test)                                      # 단위·통합
ADMIN_TOKEN=test-secret scripts/verify.sh http://localhost:8080     # 실행 중인 API 필요
ADMIN_TOKEN=test-secret CHROMIUM=/opt/pw-browsers/chromium \
  node scripts/ui-verify.mjs                                        # 브라우저
```

토큰을 빠뜨리면 뒤의 둘이 실패한다. `verify.sh`는 정책 변경이 401이 되고, 브라우저 검증의
「권한 없는 쓰기」 절은 서버 가드가 꺼져 있으면 잴 것이 없다.

검증 **건수는 `docs/00-requirements-traceability.md`의 「검증 총계」 한 곳에서만
관리**한다. README·02-tooling·04-deployment에는 숫자를 적지 않는다. 같은 숫자를
네 문서에 복사했다가 세 번 어긋난 전례가 있다.

## 이 도메인의 불변식 (리뷰 네 번으로 확정된 것)

바꾸려면 근거를 문서에 남기고 먼저 확인받을 것.

- **`DECLARING_EXTENSIONS`에 확장자를 넣는 기준은 "그 확장자가 형식 자체를 이름
  짓는가"이지, "그 형식이 흔히 그렇게 불리는가"가 아니다.** 이 기준으로 `msi`(OLE
  복합 문서), `bin`(범용 컨테이너), `out`(파일명 관례)을 제거했다. 기준을 느슨하게
  하면 제거한 것들이 같은 논리로 되돌아온다.
- **모호한 시그니처는 정직 판정의 근거가 될 수 없다.** 구분할 수 없으면 거부한다.
  `CAFEBABE`(Java class / Mach-O fat)가 그 사례다. 값 범위로 구분하려던 이전
  구현은 공격자가 고를 수 있는 값에 기댄 휴리스틱이라 제거했다.
- **탐지기는 매직 넘버만 대조하며 파일 구조를 검증하지 않는다.** `PE_EXE` 보고를
  "유효한 Windows 실행 파일"로 서술하지 말 것. 테스트 픽스처도 매직 넘버 접두사다.
- **픽스처가 규칙을 우회하면 그 규칙은 검증되지 않는다.** 실행 파일 테스트에는
  반드시 실제 시그니처 바이트를 쓸 것. 모든 `hello.exe` 픽스처가 텍스트였던 탓에
  137건이 전부 통과하면서 핵심 기능의 고장을 놓친 적이 있다.
- **고정 확장자는 데이터가 아니라 코드다.** 읽기 경로는 `FixedExtensions.ALL`을
  순회한다. DB 행이 지워져도 화면은 7개를 렌더한다.

## 확정된 결정 (재논의하지 말 것)

| 항목 | 결정 |
|---|---|
| 백엔드 호스팅 | 기존 OCI 재사용 |
| 프론트엔드 | 바닐라 JS, 런타임 의존성 0 |
| 저장소 쿼터 / 보존 | 10GB / 30일 |
| DB 마이그레이션 | one-shot 컨테이너로 분리 |
| CAFEBABE 심화 구조 검증 | 보류 (`.class`/`.dylib` 지원이 실제 요구가 될 때 재검토) |

## 배포 상태

`api.algoj.duckdns.org` (nginx → `127.0.0.1:18080`, Let's Encrypt) + Vercel 프론트로
운영 중. 배포 절차와 **배포 직후 화면에 관리자 토큰을 넣어야 하는 단계**는
`docs/04-deployment.md`.

## 남은 작업 (순서대로, 각각 별도 PR)

1. ~~CI — GitHub Actions~~
2. ~~저장소 고갈 방어~~ — 쿼터 10GB / 보존 30일 cleanup, 고아 파일 정리, 부분 파일
3. ~~DB 권한 분리 실효화~~ — one-shot 마이그레이션 컨테이너, `SPRING_FLYWAY_*` 제거
4. ~~배포 (호스트 포트 설정값화, 실제 도메인)~~

계획된 작업은 이것으로 끝났다.

미결 항목: CAFEBABE 심화 구조 검증, OLE 복합 문서 시그니처(`D0CF11E0A1B11AE1`) 추가,
`EXPLAIN`으로 V4 인덱스 확인, 고아 스윕 스트리밍.

향후 고려사항(요구사항 아님): 관리자 인증은 지금의 `X-Admin-Token`을 유지한다. 관리자
세션 만료나 사용자별 권한이 **실제로 필요해지면** 단기 JWT 발급을 검토한다. 그때도
얻는 것과 얻지 못하는 것은 `docs/01-decisions.md` 4-5와 함께 따져볼 것.
