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
