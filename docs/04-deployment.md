# 배포 가이드

구성: **프론트엔드 Vercel** + **백엔드·DB는 기존 OCI 인스턴스**

## 왜 OCI 재사용인가

무료 호스팅 후보를 조사한 결과입니다(2026-09 기준).

| 플랫폼 | 무료 MySQL | 상시 가동 | 영구 디스크 | 판정 |
|---|---|---|---|---|
| **OCI Always Free** | VM 안에서 직접 운영 → 가능 | O | 블록 볼륨 | **채택** |
| Render 무료 | ✗ (Postgres만, 90일 만료) | ✗ 15분 무트래픽 시 슬립, 재기동 약 1분 | ✗ | 부적합 |
| Railway | 30일 트라이얼뿐 | — | — | 부적합 |

결정적 요인 세 가지입니다.

1. **무료 MySQL을 주는 PaaS가 사실상 없습니다.** 요구 스택이 MySQL인데 Render 무료 DB는 Postgres 전용이고 그마저 90일 만료입니다.
2. **Render 무료는 15분 무트래픽 시 슬립**합니다. 재기동에 약 1분이 걸려, 평가자가 링크를 열었을 때 1분간 빈 화면을 보게 됩니다.
3. **Render 무료에는 영구 디스크가 없습니다.** 업로드된 파일이 재시작마다 사라져, 정상 업로드를 보여주는 기능 자체가 성립하지 않습니다.

### 확인이 필요한 전제

**2026-06-15자로 OCI Always Free ARM 한도가 절반으로 줄었습니다** — 4 OCPU / 24GB → **2 OCPU / 12GB**. 별도 공지 없이 문서만 갱신되었습니다.

기존 프로젝트가 예전 한도를 기준으로 잡혀 있다면 여유가 없을 수 있으니 배포 전 확인하세요.

```bash
# 인스턴스에서
nproc; free -h; df -h
```

이 앱은 **1 OCPU / 2GB 이내**로 동작하도록 구성했습니다(app 768MB, MySQL 512MB 상한).

---

## 1. 백엔드 (OCI)

### 사전 준비 — OCI에서 가장 자주 막히는 지점

**① Ampere A1은 ARM64입니다.**
x86 노트북에서 빌드한 이미지는 A1에서 실행되지 않습니다. **인스턴스에서 직접 빌드**하거나(가장 간단) `docker buildx build --platform linux/arm64`를 쓰세요. 이 프로젝트의 베이스 이미지는 모두 멀티아키텍처입니다.

**② 포트를 두 군데에서 열어야 합니다.**
OCI 이용자가 가장 많이 걸리는 함정입니다. VCN 보안 목록만 열고 인스턴스 방화벽을 잊으면 조용히 막힙니다.

```bash
# (1) OCI 콘솔: VCN → 서브넷 → 보안 목록에서 80/443 수신 규칙 추가

# (2) 인스턴스 방화벽
# Oracle Linux
sudo firewall-cmd --permanent --add-port=80/tcp --add-port=443/tcp && sudo firewall-cmd --reload
# Ubuntu
sudo iptables -I INPUT 1 -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT 1 -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save
```

**③ 기존 프로젝트와 공존**
- compose 프로젝트명을 `extguard`로 고정해 컨테이너·볼륨 이름이 충돌하지 않습니다.
- **MySQL은 호스트 포트를 공개하지 않습니다.** 기존 MySQL의 3306과 충돌하지 않고 외부에서 접근할 수도 없습니다.
- 앱은 **루프백에만** 바인딩합니다. 호스트 포트는 `.env`의 `APP_HOST_PORT`로 정하고 기본값이 `8080`입니다.
- **이미 8080을 쓰는 것이 있으면 반드시 바꾸세요.** compose는 빈 포트로 물러서지 않고 `port is already allocated`로 기동에 실패합니다. 먼저 확인하세요: `ss -ltn | grep :8080`
  컨테이너 안은 언제나 8080이라 Caddyfile·헬스체크·이미지의 `EXPOSE`는 이 값과 무관합니다.
- 기존 프로젝트가 이미 80/443을 쓰고 있으면 **caddy 서비스를 띄우지 말고**(기본적으로 `with-tls` 프로파일 뒤에 있어 자동 실행되지 않음) 기존 리버스 프록시에 `127.0.0.1:${APP_HOST_PORT}`로 가는 항목만 추가하세요.

**nginx를 이미 쓰고 있다면** (`APP_HOST_PORT=18080`인 경우):

```nginx
server {
    listen 80;
    # 반드시 이름을 명시합니다. 비우거나 `_`로 두면 기존 프로젝트의 catch-all 블록과
    # 어느 쪽이 이길지가 설정 파일 읽는 순서에 달리게 됩니다. 와일드카드 DNS(예:
    # DuckDNS)를 쓰면 하위 이름이 이미 기존 블록으로 들어가고 있으므로 특히 그렇습니다.
    server_name api.example.com;

    # 앱의 max-request-size가 210MB(20MB × 10개 + 여유)입니다. nginx 기본값은 1m이라
    # 이것을 두지 않으면 정상 업로드가 앱에 닿기도 전에 413으로 잘립니다. 그러면 화면에
    # 뜨는 거절 사유가 정책인지 프록시인지 구분되지 않습니다. Caddyfile의 max_size와
    # 같은 값입니다.
    client_max_body_size 220m;

    location / {
        proxy_pass http://127.0.0.1:18080;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo nginx -t && sudo systemctl reload nginx
# 인증서 발급 전에, 이 이름이 기존 블록이 아니라 여기로 오는지 먼저 확인합니다
curl -s http://api.example.com/actuator/health
sudo certbot --nginx -d api.example.com
```

### 배포

```bash
git clone <repo> && cd flow/deploy
cp .env.example .env
$EDITOR .env          # 비밀번호, 관리자 토큰, CORS 오리진 입력
```

비밀번호는 `.env`에만 있습니다. `mysql-init/01-users.sh`가 환경변수로 읽어 계정을 만들므로 **맞출 대상이 없습니다.** 계정 생성은 데이터 볼륨이 빈 상태에서 한 번만 일어나고, 나중에 바꾸려면 `ALTER USER`를 직접 실행해야 합니다.

셋 다 `openssl rand -hex 32`로 만드세요. 그중 `MIGRATOR_PASSWORD`와 `APP_DB_PASSWORD`는 SQL 리터럴에 보간되므로 **형식(영문·숫자·`_`·`-`)이 강제**되고, 어기면 계정이 만들어지지 않는 대신 스크립트가 먼저 실패합니다. `MYSQL_ROOT_PASSWORD`는 그 경로가 아니라 강제하지 않습니다.

```bash
# 관리자 토큰 생성
openssl rand -base64 32
```

```bash
docker compose up -d --build            # 기존 리버스 프록시를 쓰는 경우
docker compose --profile with-tls up -d --build   # Caddy로 TLS까지 처리하는 경우

curl localhost:${APP_HOST_PORT:-8080}/actuator/health
```

수동 단계는 없습니다. 한 줄이 끝입니다.

### 3단계로 뜹니다

```
mysql (healthy)
  └─ migrate   앱 이미지 · migrate 프로파일 · extguard_migrator   → 종료 0
       └─ grants    mysql:8.4 · root · grants.sql 적용             → 종료 0
            └─ app      앱 이미지 · extguard_app · Flyway 비활성    → 상시 기동
```

```bash
docker compose logs migrate    # 적용된 마이그레이션
docker compose logs grants     # 권한 축소
docker compose logs -f app
```

**상시 실행되는 앱은 `extguard_app` 자격증명만 받습니다.** root도 마이그레이터도 갖지 않고, `migrate`는 마이그레이터만 받습니다. 이전에는 앱이 `SPRING_FLYWAY_USER`로 마이그레이터 자격증명을 들고 있었는데, 그 계정은 `ALL PRIVILEGES`라 앱을 장악한 쪽이 환경변수를 읽어 그대로 쓸 수 있었습니다. 계정을 나눠도 한쪽이 다른 쪽 비밀번호를 갖고 있으면 분리가 아닙니다.

`mysql`은 계정을 만들어야 하므로 세 비밀번호를 다 받고 `grants`는 root를 받습니다. 둘 다 부팅에만 관여하고, 공격 표면이 되는 **오래 떠 있는 프로세스는 앱뿐**입니다.

권한 축소도 더는 사람 손에 달려 있지 않습니다. 예전에는 마이그레이션 뒤에 직접 실행하는 단계였고 빠뜨리면 앱 계정이 `extguard.*` 전체 권한을 유지했습니다. 지금은 `grants` 컨테이너가 **매 배포마다** 다시 적용합니다.

### 재부팅 후에는 `docker compose up -d`

`docker start`가 아닙니다. `service_completed_successfully` 조건은 `up` 시점에 평가되므로, 컨테이너를 개별로 start하면 migrate·grants를 건너뛴 채 앱만 뜹니다. 스키마가 이미 맞다면 당장은 돌지만 **권한 재적용이 빠집니다.**

### 확인

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "SHOW GRANTS FOR 'extguard_app'@'%';"
```

`fixed_extension_state`에 `INSERT`나 `DELETE`가 보이면 안 되고, `extguard.*`에 대한 권한도 보이면 안 됩니다. 이 권한 설정이 **고정 확장자 방어의 마지막 층**입니다 — 애플리케이션이 탈취되어 임의 SQL을 실행하더라도 고정 확장자 행을 지우거나 만들 수 없습니다.

마이그레이션이 밀린 경우, 앱은 `ddl-auto: validate` 때문에 **기동을 거부합니다.** compose 의존 조건이 1차 방어, 이게 2차입니다. 앱은 Flyway를 돌리지 않으므로 스스로 스키마를 고치지 않습니다.

### 검증

```bash
ADMIN_TOKEN=<토큰> ../scripts/verify.sh https://api.example.com
```

---

## 2. 프론트엔드 (Vercel)

빌드 단계가 없는 정적 파일입니다.

1. Vercel에서 저장소를 임포트하고 **Root Directory를 `frontend`** 로 지정
2. Framework Preset은 **Other**, 빌드 명령은 비움
3. `frontend/index.html`의 API 주소를 확인합니다. **커밋된 값이 그대로 배포되는 값**입니다 — 빌드 단계가 없으므로 Vercel은 이 파일을 있는 그대로 냅니다. 이 값을 환경마다 바꿔주는 장치는 없습니다.

```html
<meta name="api-base" content="https://api.algoj.duckdns.org">
```

> API 주소를 빌드 시 상수가 아니라 `<meta>` 태그에서 읽는 이유는, 같은 정적 번들을 빌드 파이프라인 없이 배포하기 위해서입니다. 환경을 바꾸려면 이 한 줄을 고쳐 커밋합니다.
>
> 그래서 로컬에서 이 파일을 그대로 열면 **브라우저가 운영 API로 갑니다.** `scripts/ui-verify.mjs`는 이 태그를 자기가 겨냥한 주소(`API` 환경변수, 기본 `localhost:8080`)로 고쳐 쓴 뒤 검사하므로 로컬 검증은 영향을 받지 않습니다. 근거는 [`01-decisions.md`](01-decisions.md) 4-6.

4. `frontend/vercel.json`의 CSP `connect-src`에 API 도메인을 넣습니다. 여기가 비어 있으면 브라우저가 API 호출을 차단합니다.

```json
"connect-src 'self' https://api.algoj.duckdns.org http://localhost:8080"
```

> `http://localhost:8080`은 같은 번들을 로컬에서 열어볼 때를 위해 남겨둔 것입니다. 운영 페이지가 로컬 주소로 요청을 보낼 일은 없으므로 두어도 무해하고, 빼도 배포에는 지장이 없습니다.

5. 배포 후 백엔드 `.env`의 `CORS_ALLOWED_ORIGINS`에 Vercel 도메인을 추가하고 앱을 재시작합니다.

```bash
docker compose up -d app
```

---

## 3. 로컬 개발

### MySQL 없이 (가장 빠름)

```bash
cd backend
./gradlew bootRun --args='--spring.profiles.active=dev'
```

인메모리 H2를 MySQL 호환 모드로 쓰며 **운영과 동일한 Flyway 마이그레이션**을 적용합니다. CHECK 제약도 그대로 동작합니다. 재시작하면 데이터는 사라집니다.

프론트엔드는 정적 서버면 무엇이든 됩니다.

```bash
cd frontend && python3 -m http.server 8081
# http://localhost:8081
```

`application-dev.yml`의 CORS 허용 목록에 `localhost:8081`이 이미 있습니다.

### MySQL 포함

```bash
cd deploy && cp .env.example .env && docker compose up -d
```

---

## 4. 검증 절차

```bash
# 1) 백엔드 단위·통합 테스트
cd backend && ./gradlew test

# 2) API 엔드투엔드
scripts/verify.sh http://localhost:8080

# 3) 브라우저 검증 (Chromium 필요)
npm install playwright
node scripts/ui-verify.mjs      # FRONTEND/API 환경변수로 주소 지정 가능
```

각 계층의 검증 건수는 [`00-requirements-traceability.md`](00-requirements-traceability.md)의 「검증 총계」를 참고하세요. 숫자를 여러 문서에 복사하면 어긋나므로 그 표 한 곳에서만 관리합니다.

---

## 5. 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/extguard?...` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `extguard` | 그 컨테이너의 **유일한** DB 계정. `app`은 `extguard_app`, `migrate`는 `extguard_migrator` |
| `SPRING_PROFILES_ACTIVE` | — | `migrate`면 스키마만 올리고 종료. 앱 컨테이너는 비움 |
| `SPRING_FLYWAY_ENABLED` | `true` | 앱 컨테이너에서 `false`. 마이그레이션은 `migrate`만 합니다 |
| `EXTGUARD_ADMIN_TOKEN` | *(빈 값)* | 정책 쓰기 토큰. **비우면 인증이 비활성화되고 시작 시 WARN** |
| `CORS_ALLOWED_ORIGINS` | `localhost:5173,3000` | 쉼표 구분. Vercel 도메인 필수 |
| `STORAGE_ROOT` | `/var/lib/extguard/files` | 업로드 저장 루트. 웹 루트 밖이어야 함 |
| `STORAGE_QUOTA` | `10GB` | 살아 있는 업로드 총량 상한. 초과하면 새 업로드를 **507**로 거부 |
| `STORAGE_MIN_FREE_SPACE` | `1GB` | 파일시스템에 남겨둘 최소 여유. 쿼터와 **별개로** 검사 |
| `STORAGE_RETENTION` | `30d` | 이 기간이 지난 **파일** 삭제. `upload_record` 행은 남고 `purged_at`이 채워짐 |
| `STORAGE_CLEANUP_CRON` | `0 30 3 * * *` | 정리 작업 실행 시각 (Spring 6필드 cron) |
| `SERVER_PORT` | `8080` | 컨테이너 **안에서** 앱이 듣는 포트. compose는 이 값을 바꾸지 않습니다 |

호스트 쪽 포트는 앱 환경변수가 아니라 compose 설정입니다.

| 변수 | 기본값 | 설명 |
|---|---|---|
| `APP_HOST_PORT` | `8080` | 호스트가 공개하는 포트 (`127.0.0.1:${APP_HOST_PORT}:8080`). 리버스 프록시가 가리킬 곳 |

애플리케이션 설정(`extguard.*`)은 `backend/src/main/resources/application.yml` 참조.

> **쿼터를 늘릴 때**는 디스크 실제 용량도 함께 확인하세요. 두 한도는 독립이며, 쿼터에 여유가 있어도 `STORAGE_MIN_FREE_SPACE` 아래로 내려가면 업로드가 거부됩니다. 의도된 동작입니다 — 앱 쿼터는 이 애플리케이션의 예산일 뿐 같은 디스크를 쓰는 다른 것에 대해 아무것도 모릅니다.

---

## 6. 운영 점검

아래 `$PORT`는 `.env`의 `APP_HOST_PORT`입니다 (기본 8080). `cd deploy && PORT=$(grep -E '^APP_HOST_PORT=' .env | cut -d= -f2)` 로 꺼내 쓰면 됩니다.

```bash
# 헬스
curl -s localhost:$PORT/actuator/health

# 정책 변경 이력
curl -s -H "X-Admin-Token: $TOKEN" localhost:$PORT/api/v1/policy/audit | jq

# 최근 업로드 (거부 포함)
curl -s localhost:$PORT/api/v1/files | jq

# 고정 확장자 7행 무결성
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" extguard \
  -e "SELECT COUNT(*) FROM fixed_extension_state;"   # 반드시 7

# 디스크 사용량
docker compose exec app du -sh /var/lib/extguard/files

# 쿼터 기준 사용량 (정리되지 않은 업로드의 합계)
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" extguard \
  -e "SELECT COALESCE(SUM(size_bytes),0) FROM upload_record
      WHERE status='ACCEPTED' AND purged_at IS NULL;"

# 정리 작업이 돌고 있는지
docker compose logs app | grep "Storage maintenance finished"
```

### 저장소 정리

매일 `STORAGE_CLEANUP_CRON` 시각에 두 가지를 실행합니다.

1. **보존 만료** — `STORAGE_RETENTION`이 지난 파일을 지우고 행에 `purged_at`을 기록
2. **고아 정리** — DB에 없는 파일과 버려진 `.part` 제거. 24시간 유예를 두어 **진행 중인 업로드는 건드리지 않습니다**

양쪽 모두 멱등이라 중단돼도 다음 회차가 이어받습니다. 즉시 돌려야 하면 `STORAGE_CLEANUP_CRON`을 짧게 주고 재기동하세요.

> 업로드가 507로 거부되기 시작하면 위 사용량 쿼리부터 확인하세요. 합계가 쿼터에 닿았으면 쿼터를 늘리거나 보존 기간을 줄이고, 합계는 여유가 있는데 거부된다면 **디스크 쪽**입니다(`df -h`).

### 백업

```bash
docker compose exec -T mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" extguard \
  | gzip > extguard-$(date +%F).sql.gz

docker run --rm -v extguard_uploads:/data -v "$PWD:/backup" alpine \
  tar czf /backup/uploads-$(date +%F).tar.gz -C /data .
```

### 주의

- 업로드 볼륨에는 정리 잡이 없습니다. 디스크 사용량을 주기적으로 확인하세요.
- `fixed_extension_state` 행이 7개가 아니면 조회 시마다 WARN이 남습니다. 발견되면 마이그레이션을 다시 적용하세요(런타임 계정은 복구할 권한이 없습니다 — 의도된 설계입니다).
