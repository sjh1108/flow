# deploy

기존 OCI 인스턴스에 얹는 배포 스택입니다. 전체 절차와 OCI 관련 함정은
[`../docs/04-deployment.md`](../docs/04-deployment.md)를 보세요. 여기에는 이 디렉터리의
파일이 무엇을 하는지만 적습니다.

| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | app + MySQL 8.4 + (선택) Caddy |
| `mysql-init/01-users.sql` | MySQL 최초 기동 시 1회 실행. 계정 2개 생성 |
| `mysql-init/02-grants.sql` | **마이그레이션 이후 수동 실행.** 애플리케이션 계정 권한 축소 |
| `Caddyfile` | 자동 TLS 리버스 프록시 (`with-tls` 프로파일) |
| `.env.example` | `.env`로 복사해 채울 템플릿 |

## 순서가 중요한 이유

```
1. docker compose up -d --build
      └ 01-users.sql 실행 (DB·계정 생성)
      └ Flyway가 extguard_migrator로 접속해 테이블 생성

2. mysql-init/02-grants.sql 수동 적용   ← 여기가 빠지기 쉬움
      └ extguard_app의 권한을 테이블 단위로 축소
```

MySQL은 **존재하지 않는 테이블에 권한을 줄 수 없습니다.** 그래서 테이블 단위 권한은
컨테이너 초기화 시점이 아니라 마이그레이션 이후에 적용해야 합니다. 01-users.sql이
임시로 넓은 권한을 주고, 02-grants.sql이 그것을 회수한 뒤 필요한 것만 다시 부여합니다.

```bash
docker compose exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < mysql-init/02-grants.sql
```

## 02-grants.sql이 실제로 하는 일

```sql
-- 고정 확장자: 읽기와 토글만. INSERT 없음. DELETE 없음.
GRANT SELECT, UPDATE ON extguard.fixed_extension_state TO 'extguard_app'@'%';

-- 커스텀 확장자: 사용자가 추가·삭제하므로 행 제어 필요
GRANT SELECT, INSERT, DELETE ON extguard.custom_extension TO 'extguard_app'@'%';

-- 로그는 append-only
GRANT SELECT, INSERT ON extguard.policy_audit_log TO 'extguard_app'@'%';
GRANT SELECT, INSERT ON extguard.upload_record   TO 'extguard_app'@'%';
```

고정 확장자를 지키는 마지막 층입니다. 애플리케이션 코드는 이미 고정 확장자를 지울 수
있는 경로가 없고 CHECK 제약도 걸려 있지만, 이 권한 설정은 **애플리케이션이 탈취되어
임의 SQL을 실행하는 경우**까지 막습니다. 7개 행을 만들 수 있는 것은 마이그레이션 계정뿐입니다.

확인:

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "SHOW GRANTS FOR 'extguard_app'@'%';"
```

`fixed_extension_state`에 `INSERT`나 `DELETE`가 보이면 안 됩니다.

## 기존 프로젝트와 공존

이 인스턴스에서 다른 프로젝트가 이미 돌고 있다는 전제로 구성했습니다.

- compose 프로젝트명이 `extguard`로 고정되어 컨테이너·볼륨 이름이 충돌하지 않습니다
- **MySQL은 호스트 포트를 공개하지 않습니다** — 기존 MySQL의 3306과 충돌 없음
- 앱은 `127.0.0.1:8080`에만 바인딩
- Caddy는 `with-tls` 프로파일 뒤에 있어 **기본적으로 뜨지 않습니다.** 기존 리버스 프록시가
  이미 80/443을 쓰고 있다면 그대로 두고, 거기에 `127.0.0.1:8080`으로 가는 항목만 추가하세요

## 비밀값

`.env`의 `MIGRATOR_PASSWORD` / `APP_DB_PASSWORD`는 `mysql-init/01-users.sql`의 값과
일치해야 합니다. 01-users.sql은 MySQL 최초 기동 시 한 번만 실행되므로, 나중에 바꾸려면
`ALTER USER`를 직접 실행해야 합니다.

`.env`는 커밋하지 마세요.
