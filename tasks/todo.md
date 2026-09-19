# Todo

현재 작업의 계획과 결과를 적는다. 완료된 작업은 PR 머지 후 지운다.

---

## 현재: CI 구성 (PR)

- [x] `.github/workflows/ci.yml` — push(main) / pull_request 트리거
- [x] Temurin 21 + Gradle 캐시
- [x] `./gradlew test` (H2라 외부 서비스 불필요)
- [x] `bootJar` → dev 프로파일 기동 → `/actuator/health` 대기 → `scripts/verify.sh`
- [x] 실패 시 앱 로그와 테스트 리포트 업로드
- [x] 브라우저 검증은 1단계 제외 (Playwright 설치 비용)
- [x] `CLAUDE.md` 보강 — 프로젝트 개요, 문서 읽는 순서, 검증 방법, 불변식,
      확정된 결정, 남은 작업
- [x] `tasks/lessons.md` 초기 내용 — 리뷰 네 번에서 얻은 패턴 6건
- [x] 로컬 리허설 — 빌드 → 기동 → health → verify.sh 38건 통과 (exit 0)

### 결과

CI가 없던 동안 "전부 통과"는 전부 로컬 실행 결과였고, 한 번은 stale jar 기준이라
실제와 갈라질 뻔했다(`tasks/lessons.md` 5번). 이 워크플로가 붙으면 이후 PR은
**푸시된 커밋 기준으로** 검증되므로 그 갈라짐이 구조적으로 막힌다.

브라우저 검증을 제외한 것은 비용 때문이며, 프론트엔드 변경이 잦아지면 별도 job으로
추가한다.

---

## 다음

1. **저장소 고갈 방어** — 쿼터 10GB / 보존 30일 cleanup, 고아 파일 정리,
   `transferTo()` 실패 시 부분 파일(temp + ATOMIC_MOVE), `V3__add_upload_purged_at.sql`
2. **DB 권한 분리 실효화** — one-shot 마이그레이션 컨테이너, 앱 환경변수에서
   `SPRING_FLYWAY_*` 제거
