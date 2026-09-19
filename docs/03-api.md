# API 레퍼런스

기본 경로: `/api/v1`

## 공통 오류 형식

모든 오류가 같은 형태입니다.

```json
{
  "code": "EXTENSION_BLOCKED",
  "message": "'exe' 확장자는 차단되어 있어 업로드할 수 없습니다.",
  "detail": "파일명 'invoice.pdf.exe'의 확장자 체인 [pdf,exe] 중 'exe'가 고정 차단 목록에 있습니다.",
  "timestamp": "2026-09-18T12:00:00Z"
}
```

| 필드 | 용도 |
|---|---|
| `code` | 안정적인 기계 판독용 식별자. 화면 분기나 문의 접수에 사용 |
| `message` | 그대로 화면에 띄울 수 있는 한국어 문구 |
| `detail` | 판정의 근거. 왜 이 결론인지 설명 (없을 수 있음) |

## 인증

정책 **쓰기** 엔드포인트와 감사 로그 조회는 `X-Admin-Token` 헤더가 필요합니다.
서버에 `EXTGUARD_ADMIN_TOKEN`이 설정되지 않으면 이 검사는 비활성화되고 시작 시 WARN이 남습니다.

읽기(`GET /policy/extensions`)와 업로드는 인증이 필요하지 않습니다.

---

## 정책

### `GET /api/v1/policy/extensions`

현재 정책 전체. 인증 불필요.

```json
{
  "fixed": [
    { "extension": "bat", "blocked": false },
    { "extension": "exe", "blocked": true }
  ],
  "custom": [
    { "id": 12, "extension": "sh", "createdAt": "2026-09-18T12:00:00Z" }
  ],
  "limits": {
    "maxCustomExtensions": 200,
    "maxExtensionLength": 20,
    "customCount": 1
  }
}
```

`fixed`는 **항상 7개**입니다. 데이터베이스 행이 사라지더라도 코드 상수를 기준으로 구성되므로 목록에서 빠지지 않고 `blocked: false`로 보고됩니다.

### `PATCH /api/v1/policy/extensions/fixed/{extension}`

고정 확장자 차단 여부 토글. **인증 필요.**

```json
{ "blocked": true }
```

응답은 갱신된 정책 전체(`PolicyResponse`)입니다.

| 상태 | 코드 | 상황 |
|---|---|---|
| 404 | `FIXED_EXTENSION_UNKNOWN` | 7개 고정 확장자가 아님 |
| 401 | `UNAUTHORIZED` | 토큰 누락·불일치 |

> 고정 확장자를 **삭제하는 엔드포인트는 없습니다.** `DELETE`는 405를 반환합니다.

### `POST /api/v1/policy/extensions/custom`

커스텀 확장자 추가. **인증 필요.** 성공 시 `201`.

```json
{ "extension": "sh" }
```

입력은 정규화됩니다: `.SH`, `  sh  `, `ＳＨ` 모두 `sh`가 됩니다.

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `EXT_EMPTY` | 빈 값 |
| 400 | `EXT_TOO_LONG` | 정규화 후 20자 초과 |
| 400 | `EXT_CONTAINS_DOT` | 점 포함 (`tar.gz`) |
| 400 | `EXT_CONTAINS_WHITESPACE` | 공백 포함 |
| 400 | `EXT_INVALID_CHARSET` | `[a-z0-9]` 외 문자 |
| 409 | `EXT_IS_FIXED` | 고정 확장자를 커스텀으로 추가 시도 |
| 409 | `EXT_DUPLICATE` | 이미 등록됨 |
| 409 | `CUSTOM_LIMIT_EXCEEDED` | 200개 초과 |

### `DELETE /api/v1/policy/extensions/custom/{extension}`

커스텀 확장자 삭제. **인증 필요.**

| 상태 | 코드 | 상황 |
|---|---|---|
| 404 | `EXT_NOT_FOUND` | 등록되어 있지 않음. 고정 확장자를 넘긴 경우도 여기에 해당하며, `detail`이 "체크 해제로 차단을 해제하세요"라고 안내 |

### `GET /api/v1/policy/audit?limit=50`

정책 변경 이력. **인증 필요.** 최신순.

```json
[{
  "id": 3,
  "action": "FIXED_BLOCKED",
  "extension": "exe",
  "extensionType": "FIXED",
  "beforeValue": "false",
  "afterValue": "true",
  "actor": "admin-token",
  "actorIp": "203.0.113.10",
  "createdAt": "2026-09-18T12:00:00Z"
}]
```

`action`: `FIXED_BLOCKED` · `FIXED_UNBLOCKED` · `CUSTOM_ADDED` · `CUSTOM_REMOVED`

---

## 파일

### `POST /api/v1/files`

`multipart/form-data`, 파트 이름 `files` (복수 가능). 인증 불필요.

| 상태 | 의미 |
|---|---|
| **200** | 1건 이상 성공 |
| **422** | 전부 거부 |

두 경우 **본문 형태가 동일**하므로 클라이언트는 상태 코드로 분기하지 않고 `results`를 그대로 렌더하면 됩니다.

```json
{
  "acceptedCount": 1,
  "rejectedCount": 1,
  "results": [
    {
      "filename": "notes.txt",
      "status": "ACCEPTED",
      "code": null, "message": null, "detail": null,
      "recordId": 41,
      "sizeBytes": 12,
      "sha256": "a948904f2f0f479b…",
      "detectedSignature": null
    },
    {
      "filename": "invoice.pdf.exe",
      "status": "REJECTED",
      "code": "EXTENSION_BLOCKED",
      "message": "'exe' 확장자는 차단되어 있어 업로드할 수 없습니다.",
      "detail": "파일명 'invoice.pdf.exe'의 확장자 체인 [pdf,exe] 중 'exe'가 고정 차단 목록에 있습니다.",
      "recordId": 42,
      "sizeBytes": 12,
      "sha256": null,
      "detectedSignature": null
    }
  ]
}
```

#### 파일별 거부 코드

| 코드 | 상황 |
|---|---|
| `EMPTY_FILE` | 0바이트 |
| `FILE_TOO_LARGE` | 20MB 초과 |
| `FILENAME_MISSING` | 파일명 없음 |
| `FILENAME_TOO_LONG` | UTF-8 255바이트 초과 |
| `FILENAME_CONTROL_CHAR` | 제어문자·NUL 포함 |
| `FILENAME_RESERVED` | Windows 예약어 (`CON`, `LPT1` 등) |
| `FILENAME_INVALID` | 정규화 후 남는 이름 없음 |
| `EXTENSION_BLOCKED` | 확장자 체인이 차단 목록과 교집합 |
| `EXECUTABLE_CONTENT` | 내용이 실행파일·스크립트, 또는 이미지를 자칭한 폴리글롯 |
| `CONTENT_TYPE_MISMATCH` | 내용이 확장자와 불일치 |

#### 요청 단위 오류

| 상태 | 코드 | 상황 |
|---|---|---|
| 400 | `NO_FILE_SUBMITTED` | `files` 파트 없음 |
| 413 | `TOO_MANY_FILES` | 10개 초과 |
| 413 | `FILE_TOO_LARGE` | 컨테이너가 컨트롤러 진입 전 거부 |

#### 검사 순서

메시지 품질이 순서에 달려 있습니다. 여러 규칙에 동시에 걸리면 **관리자가 설정한 정책**을 먼저 알려줍니다.

```
R1 빈 파일
R2 크기 초과
R3 파일명 구조 위반
R4 확장자 체인 ∩ 차단 목록      ← 설정된 정책이 우선
R5 내용이 실행파일·스크립트
R6 내용이 확장자와 불일치
R7 선언 MIME 불일치             ← 기록만, 거부하지 않음
```

### `GET /api/v1/files?limit=20`

최근 업로드 이력. **거부된 건도 포함**됩니다. 인증 불필요.

```json
[{
  "id": 42,
  "originalFilename": "invoice.pdf.exe",
  "extensionChain": "pdf,exe",
  "sizeBytes": 12,
  "status": "REJECTED",
  "rejectionCode": "EXTENSION_BLOCKED",
  "rejectionDetail": "파일명 'invoice.pdf.exe'의 …",
  "detectedSignature": null,
  "sha256": null,
  "createdAt": "2026-09-18T12:00:00Z"
}]
```

> 업로드된 파일을 다시 내려받는 엔드포인트는 **의도적으로 제공하지 않습니다.** 사용자 파일을 브라우저로 되돌려주는 것이 실제 위험 지점이며, 정책 강제를 보이는 데 필요하지 않습니다.

### `GET /actuator/health`

```json
{ "status": "UP" }
```
