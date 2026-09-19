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
const API = `${process.env.API || 'http://localhost:8080'}/api/v1/policy/extensions`;
const TOKEN = process.env.ADMIN_TOKEN || '';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'extguard-ui-'));
const fixture = (name, content) => {
  const file = path.join(tmp, name);
  fs.writeFileSync(file, content);
  return file;
};
// Real PE bytes, not text: the exe checkbox has to govern genuine executables,
// and an earlier version of this script used text content so it never did.
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

await resetPolicy();

const browser = await chromium.launch({
  ...(process.env.CHROMIUM ? { executablePath: process.env.CHROMIUM } : {}),
  args: ['--no-sandbox'],
});
const page = await browser.newPage({ viewport: { width: 1000, height: 1200 } });

const scriptErrors = [];
page.on('pageerror', (e) => scriptErrors.push(String(e)));
page.on('console', (message) => {
  // Chromium logs "Failed to load resource" for any non-2xx response, and a 422
  // is the expected answer for a blocked upload. Only real script errors count.
  if (message.type() === 'error' && !message.text().includes('Failed to load resource')) {
    scriptErrors.push(message.text());
  }
});

await page.goto(FRONTEND, { waitUntil: 'networkidle' });

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

console.log('\n3. [CORE] 정책이 실제 업로드에 강제되는지');
await page.setInputFiles('#file-input', HELLO_EXE);
await page.waitForSelector('.result.is-accepted', { timeout: 15000 });
check('exe 미체크 상태에서 진짜 PE 실행파일 업로드 성공', true);

await page.click('.chip:has(.chip-label:text-is("exe")) input');
await page.waitForSelector('.chip.is-blocked', { timeout: 10000 });
check('exe 체크박스가 차단 상태로 전환', true);

await page.setInputFiles('#file-input', HELLO_EXE);
await page.waitForSelector('.result.is-rejected', { timeout: 15000 });
const rejected = await page.$('.result.is-rejected');
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
await page.waitForSelector('.result.is-accepted', { timeout: 15000 });
check('notes.txt는 정상 업로드', true);
check('브라우저 스크립트 오류 없음', scriptErrors.length === 0, scriptErrors.join(' | '));

if (process.env.SCREENSHOT) {
  await page.screenshot({ path: process.env.SCREENSHOT, fullPage: true });
  console.log(`\n  스크린샷: ${process.env.SCREENSHOT}`);
}

await browser.close();
await resetPolicy();
fs.rmSync(tmp, { recursive: true, force: true });

console.log(`\n결과  통과: ${pass}  실패: ${fail}\n`);
process.exit(fail === 0 ? 0 : 1);
