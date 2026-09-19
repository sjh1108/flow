# deploy

기존 OCI 인스턴스에 얹는 배포 스택입니다. 전체 절차와 OCI 관련 함정은
[`../docs/04-deployment.md`](../docs/04-deployment.md)를 보세요. 여기에는 이 디렉터리의
파일이 무엇을 하는지만 적습니다.

| 파일 | 역할 |
|---|---|
| `docker-compose.yml` | MySQL 8.4 → migrate → grants → app + (선택) Caddy |
| `mysql-init/01-users.sh` | MySQL 최초 기동 시 1회 실행. 계정 2개 생성 |
| `grants.sql` | `grants` 컨테이너가 매 배포마다 적용. 앱 계정 권한을 테이블 단위로 한정 |
| `Caddyfile` | 자동 TLS 리버스 프록시 (`with-tls` 프로파일) |
| `.env.example` | `.env`로 복사해 채울 템플릿 |

## 3단계로 뜹니다

```
mysql (healthy)
  └─ migrate   앱 이미지 · migrate 프로파일 · extguard_migrator   → 종료 0
       └─ grants    mysql:8.4 · root · grants.sql 적용             → 종료 0
            └─ app      앱 이미지 · extguard_app · Flyway 비활성    → 상시 기동
```

```bash
docker compose up -d --build
```

한 줄이면 됩니다. 수동 단계는 없습니다.

### 왜 이렇게 나눴나

**앱이 마이그레이터 비밀번호를 갖고 있으면 권한 분리가 아닙니다.** 이전 구성은 앱
컨테이너에 `SPRING_FLYWAY_USER=extguard_migrator`와 그 비밀번호를 넘겨 Flyway를 앱 안에서
돌렸습니다. `extguard_migrator`는 `ALL PRIVILEGES`이므로, 앱을 장악한 쪽은 자기 환경변수를
읽어 그 계정으로 붙으면 그만이었습니다. `grants.sql`이 막는다고 적어둔 것이 전부
우회됐습니다 — 그리고 그 파일이 내세우는 위협 모델이 정확히 "앱이 탈취되어 임의 SQL을
실행하는 경우"입니다.

이제 **컨테이너마다 계정이 하나씩**이고 서로의 것을 모릅니다.

**그리고 `grants.sql` 적용이 더는 사람 손에 달려 있지 않습니다.** 예전에는 마이그레이션
뒤에 직접 실행하는 단계였고, 이 문서에도 "여기가 빠지기 쉬움"이라고 적혀 있었습니다.
빠뜨리면 앱 계정이 `extguard.*` 전체에 대한 권한을 그대로 유지했고 아무도 알려주지
않았습니다. 지금은 컨테이너가 하고, 매 배포마다 다시 적용해 권한을 좁게 **유지**합니다.

덕분에 `01-users.sh`가 앱 계정에 주던 임시 광범위 권한도 없앨 수 있었습니다. grants가
app보다 먼저 끝나므로 메울 공백이 없고, **앱 계정은 단 한 순간도 스키마 전체 권한을 갖지
않습니다.**

### 재부팅 후에는 `docker compose up -d`

`docker start`가 아닙니다. `service_completed_successfully` 조건은 `up` 시점에 평가되므로,
컨테이너를 개별로 start하면 migrate·grants를 건너뛴 채 앱만 뜹니다. 스키마가 이미 맞다면
당장은 돌지만 **권한 재적용이 빠집니다.**

### 마이그레이션이 밀렸을 때

앱은 `spring.jpa.hibernate.ddl-auto: validate`라 자기가 아는 스키마가 아니면 **기동을
거부합니다.** compose 의존 조건이 1차 방어, 이게 2차입니다. 앱은 Flyway를 돌리지 않으므로
(`SPRING_FLYWAY_ENABLED=false`) 스스로 스키마를 고치지 않습니다 — 고치는 것은 migrate뿐입니다.

## grants.sql이 실제로 하는 일

```sql
-- 고정 확장자: 읽기와 토글만. INSERT 없음. DELETE 없음.
GRANT SELECT, UPDATE ON extguard.fixed_extension_state TO 'extguard_app'@'%';

-- 커스텀 확장자: 사용자가 추가·삭제하므로 행 제어 필요
GRANT SELECT, INSERT, DELETE ON extguard.custom_extension TO 'extguard_app'@'%';

-- 로그는 append-only
GRANT SELECT, INSERT ON extguard.policy_audit_log TO 'extguard_app'@'%';
GRANT SELECT, INSERT ON extguard.upload_record   TO 'extguard_app'@'%';

-- 유일한 예외. 정리 작업이 "파일을 지웠다"는 사실만 기록하도록 컬럼 단위로 연다.
GRANT UPDATE (purged_at) ON extguard.upload_record TO 'extguard_app'@'%';
```

고정 확장자를 지키는 마지막 층입니다. 애플리케이션 코드는 이미 고정 확장자를 지울 수
있는 경로가 없고 CHECK 제약도 걸려 있습니다. 7개 행을 만들 수 있는 것은 마이그레이션
계정뿐입니다.

`grants.sql`이 `mysql-init/`에 있지 않은 이유는, 그 디렉터리의 파일은 DB **최초 기동 시**
실행되는데 그때는 테이블이 없기 때문입니다. 테이블 단위 GRANT는 마이그레이션 이후에만
가능합니다.

확인:

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "SHOW GRANTS FOR 'extguard_app'@'%';"
```

`fixed_extension_state`에 `INSERT`나 `DELETE`가 보이면 안 됩니다. `extguard.*`에 대한
권한도 보이면 안 됩니다.

## 기존 프로젝트와 공존

이 인스턴스에서 다른 프로젝트가 이미 돌고 있다는 전제로 구성했습니다.

- compose 프로젝트명이 `extguard`로 고정되어 컨테이너·볼륨 이름이 충돌하지 않습니다
- **MySQL은 호스트 포트를 공개하지 않습니다** — 기존 MySQL의 3306과 충돌 없음
- 앱은 `127.0.0.1:8080`에만 바인딩
- Caddy는 `with-tls` 프로파일 뒤에 있어 **기본적으로 뜨지 않습니다.** 기존 리버스 프록시가
  이미 80/443을 쓰고 있다면 그대로 두고, 거기에 `127.0.0.1:8080`으로 가는 항목만 추가하세요

## 비밀값

세 비밀번호는 `.env`에만 있습니다. 커밋된 파일에는 없습니다. `01-users.sh`가 환경변수로
읽으므로 **맞춰야 할 대상이 없습니다.**

`.env`는 커밋하지 마세요. 계정 생성은 데이터 볼륨이 빈 상태에서 한 번만 일어나므로,
나중에 비밀번호를 바꾸려면 `ALTER USER`를 직접 실행해야 합니다.
