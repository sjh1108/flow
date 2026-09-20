/**
 * Browser verification of the policy screen and upload flow.
 *
 * Drives a real Chromium against a running frontend and API, so it covers what
 * unit and integration tests cannot: whether the page actually renders, whether
 * the checkbox really persists across a reload, and whether a rejection reason
 * reaches the screen. An empty error banner bug was found here after the whole
 * backend suite was already green.
 *
 * Usage:
 *   npm install playwright
 *   node scripts/ui-verify.mjs
 *   FRONTEND=https://app.vercel.app API=https://api.example.com node scripts/ui-verify.mjs
 *
 * CHROMIUM may be set to a browser binary; otherwise Playwright's default is used.
 */

import { chromium } from 'playwright';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const FRONTEND = process.env.FRONTEND || 'http://127.0.0.1:8081/index.html';
const API_BASE = process.env.API || 'http://localhost:8080';
const API = `${API_BASE}/api/v1/policy/extensions`;
const AUDIT = `${API_BASE}/api/v1/policy/audit`;
const TOKEN = process.env.ADMIN_TOKEN || '';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'extguard-ui-'));
const fixture = (name, content) => {
  const file = path.join(tmp, name);
  fs.writeFileSync(file, content);
  return file;
};
// The PE magic number (MZ), not text. This is a magic-number prefix rather than
// a valid executable -- the detector matches leading bytes only. An earlier
// version used text content, so the exe checkbox was never actually exercised.
const HELLO_EXE = fixture('hello.exe', Buffer.from([0x4d, 0x5a, 0x90, 0x00, 0x03, 0x00, 0x00, 0x00]));
const NOTES_TXT = fixture('notes.txt', 'hello world');

let pass = 0;
let fail = 0;
const check = (description, ok, extra = '') => {
  console.log(`  ${ok ? '✓' : '✗'} ${description}${ok ? '' : '  <-- ' + extra}`);
  ok ? pass++ : fail++;
};

const adminHeaders = TOKEN ? { 'X-Admin-Token': TOKEN } : {};

/** Start from a known state so the run does not depend on leftovers. */
async function resetPolicy() {
  const current = await (await fetch(API)).json();
  for (const item of current.fixed.filter((f) => f.blocked)) {
    await fetch(`${API}/fixed/${item.extension}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', ...adminHeaders },
      body: JSON.stringify({ blocked: false }),
    });
  }
  for (const item of current.custom) {
    await fetch(`${API}/custom/${item.extension}`, { method: 'DELETE', headers: adminHeaders });
  }
}

/** Say what is wrong and what to run, then stop. */
function abort(...reasons) {
  for (const reason of reasons) console.error(`\n  ${reason}`);
  console.error('\n  가드를 켠 서버에 같은 토큰으로 돌리세요:\n');
  console.error("    cd backend && EXTGUARD_ADMIN_TOKEN=test-secret \\");
  console.error("      ./gradlew bootRun --args='--spring.profiles.active=dev'");
  console.error('\n    # 저장소 루트에서');
  console.error('    ADMIN_TOKEN=test-secret node scripts/ui-verify.mjs\n');
  process.exit(1);
}

/**
 * Fail here, not twenty assertions later.
 *
 * Section 7 is the only part that needs the server's admin guard ON and a
 * matching token, and it needs both: without them the run fails far from the
 * cause. Guard on with no token, and the first write 401s and the run dies at a
 * selector timeout in section 2 that says nothing about tokens. Guard OFF, and
 * the anonymous toggle in section 7 SUCCEEDS -- measured: the toast reads "bat
 * 확장자를 차단했습니다" -- so the script reports the permission check as broken
 * when what is actually missing is the guard.
 *
 * So establish the state rather than rule one failure out. An earlier version
 * probed a write and treated anything but 401 as fine, which reads a guard-off
 * server as a healthy one: with the guard off the write reaches the controller
 * and comes back 400.
 *
 * The audit trail separates all four states in two reads. It is the one GET the
 * guard covers (AdminTokenFilter#requiresAdmin), so anonymous access to it is
 * 401 exactly when the guard is on -- and reading it changes nothing.
 */
async function requireAdminGuard() {
  const anonymous = await fetch(AUDIT);
  if (anonymous.status !== 401) {
    abort('서버의 관리자 가드가 꺼져 있습니다 (EXTGUARD_ADMIN_TOKEN 미설정).',
          '가드가 없으면 토큰 없는 쓰기가 그냥 성공해서 「권한 없는 쓰기」 절이 잴 것이 없습니다.');
  }
  if (!TOKEN) {
    abort('서버 가드는 켜져 있는데 ADMIN_TOKEN이 없습니다.');
  }
  const authenticated = await fetch(AUDIT, { headers: adminHeaders });
  if (authenticated.status !== 200) {
    abort('ADMIN_TOKEN이 서버의 EXTGUARD_ADMIN_TOKEN과 다릅니다.');
  }
}

await requireAdminGuard();
await resetPolicy();

const browser = await chromium.launch({
  ...(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {}),
  args: ['--no-sandbox'],
});
const page = await browser.newPage({ viewport: { width: 1000, height: 1200 } });

// config.js reads the admin token from this key. Without it the page is
// anonymous, so every write in sections 2 and 3 comes back 401 against an API
// that has EXTGUARD_ADMIN_TOKEN set -- which a deployed one does. This script
// already takes ADMIN_TOKEN for its own fetches; the browser needs the same one.
await page.addInitScript((token) => {
  if (!token) return;
  try {
    localStorage.setItem('extguard.adminToken', token);
  } catch {
    /* Private browsing or blocked storage: the page stays anonymous. */
  }
}, TOKEN);

// index.html carries the deployed API origin, because Vercel serves the file
// exactly as committed. Left alone, a locally served page would drive the
// deployed system while the setup fetches above talk to API_BASE.
//
// `?api=` is the same override a person uses when following the README quick
// start, not a hook added for this script -- so this run exercises the path the
// documentation hands to a human. config.js ignores it unless the page is
// served locally, which is why a remote FRONTEND still uses its own tag.
const target = new URL(FRONTEND);
target.searchParams.set('api', API_BASE);
const PAGE = target.toString();

const scriptErrors = [];
page.on('pageerror', (e) => scriptErrors.push(String(e)));
page.on('console', (message) => {
  // Chromium logs "Failed to load resource" for any non-2xx response, and a 422
  // is the expected answer for a blocked upload. Only real script errors count.
  if (message.type() === 'error' && !message.text().includes('Failed to load resource')) {
    scriptErrors.push(message.text());
  }
});

await page.goto(PAGE, { waitUntil: 'networkidle' });

console.log('\n1. 정책 화면 렌더링');
await page.waitForSelector('.chip', { timeout: 15000 });
check('고정 확장자 체크박스 7개 렌더', (await page.$$('.chip')).length === 7);

const labels = await page.$$eval('.chip-label', (els) => els.map((e) => e.textContent));
check(
  '고정 확장자 목록이 정확',
  JSON.stringify(labels) === JSON.stringify(['bat', 'cmd', 'com', 'cpl', 'exe', 'scr', 'js']),
  labels.join(','),
);

const checked = await page.$$eval('.chip input', (els) => els.filter((e) => e.checked).length);
check('기본값은 전부 unchecked', checked === 0, `checked=${checked}`);
check('정상 로드 시 오류 배너가 보이지 않음', (await page.isVisible('#policy-error')) === false);
check('커스텀 카운터가 0 / 200', (await page.textContent('#custom-counter')).trim() === '0 / 200');

console.log('\n2. 커스텀 확장자');
await page.fill('#custom-input', 'sh');
await page.click('#custom-submit');
await page.waitForSelector('.tag', { timeout: 10000 });
check('sh 태그가 목록에 표시', (await page.$$('.tag')).length === 1);
check('카운터가 1 / 200으로 갱신', (await page.textContent('#custom-counter')).trim() === '1 / 200');

await page.fill('#custom-input', 'sh');
await page.click('#custom-submit');
await page.waitForSelector('#custom-input-error:not([hidden])', { timeout: 10000 });
check('중복 입력 시 인라인 오류', (await page.textContent('#custom-input-error')).includes('이미 추가된'));

await page.fill('#custom-input', 'exe');
await page.click('#custom-submit');
await page.waitForTimeout(400);
check('고정 확장자 입력 시 안내', (await page.textContent('#custom-input-error')).includes('고정 확장자'));

// Every verdict below is read off NEWEST_RESULT rather than off ".result.is-*".
// A bare class selector waits for "any row in that state", so an earlier row in
// that state ends the wait immediately; the read that follows then takes the
// first row in document order, and a row starts out .is-pending, so the stale
// one wins until the real verdict lands. Two accidents were hiding that: the
// reload in section 4 empties the list, and the stubbed response arrives fast
// enough to win the race anyway. Measured -- drop the reload and it still
// passes; add a 1.5s delay to the stub as well and section 6 reads section 3's
// EXTENSION_BLOCKED row instead. Rows are prepended, so the row under test is
// always :first-child, and anchoring there depends on neither accident. Every
// upload below sends one file -- a multi-file batch prepends a row per file,
// and :first-child would then be its last file rather than the batch.
const NEWEST_RESULT = '#upload-results > .result:first-child';

console.log('\n3. [CORE] 정책이 실제 업로드에 강제되는지');
await page.setInputFiles('#file-input', HELLO_EXE);
await page.waitForSelector(`${NEWEST_RESULT}.is-accepted`, { timeout: 15000 });
check('exe 미체크 상태에서 PE 시그니처 파일 업로드 성공', true);

await page.click('.chip:has(.chip-label:text-is("exe")) input');
await page.waitForSelector('.chip.is-blocked', { timeout: 10000 });
check('exe 체크박스가 차단 상태로 전환', true);

await page.setInputFiles('#file-input', HELLO_EXE);
await page.waitForSelector(`${NEWEST_RESULT}.is-rejected`, { timeout: 15000 });
const rejected = await page.$(NEWEST_RESULT);
const read = async (selector) => rejected.$eval(selector, (e) => e.textContent).catch(() => '');
check('동일 파일 재업로드가 화면에서 거부됨', true);
check('거부 사유가 exe를 명시', (await read('.result-message')).includes('exe'));
check('근거(detail)가 함께 표시됨', (await read('.result-detail')).includes('차단 목록'));
check('오류 코드가 EXTENSION_BLOCKED', (await read('.result-code')).trim() === 'EXTENSION_BLOCKED');

console.log('\n4. 새로고침 후 상태 유지');
await page.reload({ waitUntil: 'networkidle' });
await page.waitForSelector('.chip', { timeout: 15000 });
check(
  '새로고침 후에도 exe가 체크 상태',
  (await page.$eval('.chip:has(.chip-label:text-is("exe")) input', (e) => e.checked)) === true,
);
check('새로고침 후에도 커스텀 sh 유지', (await page.$$('.tag')).length === 1);

console.log('\n5. 정상 파일 / 스크립트 오류');
await page.setInputFiles('#file-input', NOTES_TXT);
await page.waitForSelector(`${NEWEST_RESULT}.is-accepted`, { timeout: 15000 });
check('notes.txt는 정상 업로드', true);
check('브라우저 스크립트 오류 없음', scriptErrors.length === 0, scriptErrors.join(' | '));

// The server refuses a full disk with 507 and a per-file verdict. The client
// used to resolve only 200 and 422, so a 507 fell through to the generic error
// path: every row read "전송 실패" and the reason the server had filled in was
// discarded. Stubbed rather than driven through a real full disk, because the
// defect is in how the client reads the response, not in producing one.
console.log('\n6. 저장 공간 부족(507) 응답 처리');
await page.route('**/api/v1/files', async (route) => {
  if (route.request().method() !== 'POST') return route.continue();
  await route.fulfill({
    status: 507,
    contentType: 'application/json',
    body: JSON.stringify({
      acceptedCount: 0,
      rejectedCount: 1,
      results: [{
        filename: 'notes.txt',
        status: 'REJECTED',
        code: 'STORAGE_QUOTA_EXCEEDED',
        message: '저장 공간이 부족하여 업로드할 수 없습니다. 잠시 후 다시 시도해 주세요.',
        detail: '저장소 사용량이 한도에 도달했습니다. 남은 용량: 0B, 요청 크기: 11B',
        recordId: 99,
        sizeBytes: 11,
        sha256: null,
        detectedSignature: null,
      }],
    }),
  });
});

await page.setInputFiles('#file-input', NOTES_TXT);
await page.waitForSelector(`${NEWEST_RESULT}.is-rejected`, { timeout: 15000 });
const overQuota = await page.$(NEWEST_RESULT);
const readQuota = async (selector) => overQuota.$eval(selector, (e) => e.textContent).catch(() => '');
check('507도 파일별 판정으로 렌더됨 (전송 실패 아님)',
  (await readQuota('.result-status')).includes('차단'),
  await readQuota('.result-status'));
check('사유가 저장 공간 부족임을 명시', (await readQuota('.result-message')).includes('저장 공간'));
check('남은 용량 detail이 표시됨', (await readQuota('.result-detail')).includes('남은 용량'));
check('오류 코드가 STORAGE_QUOTA_EXCEEDED',
  (await readQuota('.result-code')).trim() === 'STORAGE_QUOTA_EXCEEDED');
await page.unroute('**/api/v1/files');

/* ------------------------------------------------------------------ *
 * 7. 권한 없는 쓰기
 *
 * A token-carrying page can never reach this. Every section above seeds the
 * admin token, so the 401 path was walked by nothing -- and that is how a real
 * break reached production: the filter answers from the chain, so its 401 went
 * out with no CORS header, the browser discarded it, and the page reported a
 * network failure instead of a permission problem. MockMvc and curl both read
 * that 401 happily, because neither enforces CORS. Only a browser sees it.
 *
 * A fresh context with no token in storage is what makes this measurable.
 * ------------------------------------------------------------------ */
console.log('\n7. 권한 없는 쓰기');

const anonContext = await browser.newContext();
const anonPage = await anonContext.newPage();
const corsBlocked = [];
anonPage.on('console', (message) => {
  if (message.text().includes('CORS')) corsBlocked.push(message.text());
});

await anonPage.goto(PAGE, { waitUntil: 'networkidle' });
await anonPage.waitForSelector('.chip input', { timeout: 15000 });
await anonPage.locator('.chip input').first().check();
await anonPage.waitForSelector('.toast', { timeout: 15000 });

const toastText = (await anonPage.locator('.toast').first().textContent()) ?? '';
check('토큰 없이 토글하면 권한 안내가 뜸', toastText.includes('관리자 토큰이 필요합니다'), toastText);
check('CORS로 차단되지 않음 (401이 브라우저에 도달)', corsBlocked.length === 0, corsBlocked[0] ?? '');

const reverted = await anonPage.locator('.chip input').first().isChecked();
check('거절된 토글은 원래 상태로 되돌아감', reverted === false);

await anonContext.close();

if (process.env.SCREENSHOT) {
  await page.screenshot({ path: process.env.SCREENSHOT, fullPage: true });
  console.log(`\n  스크린샷: ${process.env.SCREENSHOT}`);
}

await browser.close();
await resetPolicy();
fs.rmSync(tmp, { recursive: true, force: true });

console.log(`\n결과  통과: ${pass}  실패: ${fail}\n`);
process.exit(fail === 0 ? 0 : 1);
