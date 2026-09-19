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
- 앱은 `127.0.0.1:8080`에만 바인딩합니다.
- 기존 프로젝트가 이미 80/443을 쓰고 있으면 **caddy 서비스를 띄우지 말고**(기본적으로 `with-tls` 프로파일 뒤에 있어 자동 실행되지 않음) 기존 리버스 프록시에 `127.0.0.1:8080`으로 가는 항목만 추가하세요.

### 배포

```bash
git clone <repo> && cd flow/deploy
cp .env.example .env
$EDITOR .env          # 비밀번호, 관리자 토큰, CORS 오리진 입력
```

`.env`의 `MIGRATOR_PASSWORD` / `APP_DB_PASSWORD`를 `mysql-init/01-users.sql`의 값과 **일치**시키거나, 01-users.sql 쪽을 편집하세요. 이 파일은 MySQL 최초 기동 시 한 번만 실행됩니다.

```bash
# 관리자 토큰 생성
openssl rand -base64 32
```

```bash
docker compose up -d --build            # 기존 리버스 프록시를 쓰는 경우
docker compose --profile with-tls up -d --build   # Caddy로 TLS까지 처리하는 경우

docker compose logs -f app              # 마이그레이션 적용 확인
curl localhost:8080/actuator/health
```

### 마이그레이션 직후 — 권한 축소 (중요)

테이블이 만들어진 다음에 실행해야 합니다. MySQL은 존재하지 않는 테이블에 권한을 줄 수 없습니다.

```bash
docker compose exec -T mysql \
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" < mysql-init/02-grants.sql
```

이 단계가 **고정 확장자 방어의 마지막 층**입니다. 실행 후 애플리케이션 계정은 `fixed_extension_state`에 `SELECT`와 `UPDATE`만 갖습니다. 애플리케이션이 탈취되어 임의 SQL을 실행하더라도 고정 확장자 행을 지우거나 만들 수 없습니다.

확인:

```bash
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
  -e "SHOW GRANTS FOR 'extguard_app'@'%';"
```

`fixed_extension_state`에 `INSERT`나 `DELETE`가 보이면 안 됩니다.

### 검증

```bash
ADMIN_TOKEN=<토큰> ../scripts/verify.sh https://api.example.com
```

---

## 2. 프론트엔드 (Vercel)

빌드 단계가 없는 정적 파일입니다.

1. Vercel에서 저장소를 임포트하고 **Root Directory를 `frontend`** 로 지정
2. Framework Preset은 **Other**, 빌드 명령은 비움
3. 배포 전 `frontend/index.html`의 API 주소를 수정:

```html
<meta name="api-base" content="https://api.example.com">
```

> API 주소를 빌드 시 상수가 아니라 `<meta>` 태그에서 읽는 이유는, 같은 정적 번들을 환경만 바꿔 배포할 수 있게 하기 위해서입니다. 빌드 파이프라인 없이 이 한 줄만 고치면 됩니다.

4. `frontend/vercel.json`의 CSP `connect-src`에 API 도메인을 넣습니다. 여기가 비어 있으면 브라우저가 API 호출을 차단합니다.

```json
"connect-src 'self' https://api.example.com"
```

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
# 1) 백엔드 테스트 156건
cd backend && ./gradlew test

# 2) API 엔드투엔드 38건
scripts/verify.sh http://localhost:8080

# 3) 브라우저 검증 19건 (Chromium 필요)
npm install playwright
node scripts/ui-verify.mjs      # FRONTEND/API 환경변수로 주소 지정 가능
```

---

## 5. 환경 변수

| 변수 | 기본값 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/extguard?...` | JDBC URL |
| `DB_USERNAME` / `DB_PASSWORD` | `extguard` | 런타임 계정 (권한 축소 대상) |
| `SPRING_FLYWAY_USER` / `SPRING_FLYWAY_PASSWORD` | — | 마이그레이션 계정 |
| `EXTGUARD_ADMIN_TOKEN` | *(빈 값)* | 정책 쓰기 토큰. **비우면 인증이 비활성화되고 시작 시 WARN** |
| `CORS_ALLOWED_ORIGINS` | `localhost:5173,3000` | 쉼표 구분. Vercel 도메인 필수 |
| `STORAGE_ROOT` | `/var/lib/extguard/files` | 업로드 저장 루트. 웹 루트 밖이어야 함 |
| `SERVER_PORT` | `8080` | |

애플리케이션 설정(`extguard.*`)은 `backend/src/main/resources/application.yml` 참조.

---

## 6. 운영 점검

```bash
# 헬스
curl -s localhost:8080/actuator/health

# 정책 변경 이력
curl -s -H "X-Admin-Token: $TOKEN" localhost:8080/api/v1/policy/audit | jq

# 최근 업로드 (거부 포함)
curl -s localhost:8080/api/v1/files | jq

# 고정 확장자 7행 무결성
docker compose exec mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" extguard \
  -e "SELECT COUNT(*) FROM fixed_extension_state;"   # 반드시 7

# 디스크 사용량
docker compose exec app du -sh /var/lib/extguard/files
```

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
