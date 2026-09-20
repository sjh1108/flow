/**
 * Upload screen.
 *
 * A deliberate choice: even when the client already knows an extension is
 * blocked, the file is still sent. The verdict shown is always the server's, so
 * the screen demonstrates that the policy is enforced server-side rather than
 * merely reflecting what the client believes. The local blocklist is used only
 * to label a row as expected-to-be-blocked while it uploads.
 *
 * <h3>A request is not its files</h3>
 *
 * Selected files travel in one multipart request, and two things can go wrong at
 * two different levels:
 *
 *   - the server judged each file          -> per-row verdicts, from results[]
 *   - the request never got that far       -> one failure, belonging to nobody
 *
 * The second kind used to be copied onto every row. Fourteen files, one of them
 * 68KB, all labelled "파일 크기가 허용 범위를 초과했습니다" because the container
 * refused the request before any file was examined. The per-row progress bars
 * set that up: they were one request's progress painted N times, so the screen
 * promised N independent uploads and then had to explain one shared failure.
 *
 * So request-level things are shown at the request level -- one bar while it
 * sends, one banner if it fails -- and rows only ever carry what the server said
 * about that file. When the request fails, rows say 전송되지 않음, which is what
 * actually happened to them.
 *
 * The preflight below is not a verdict either. It refuses request shapes the
 * server has published as impossible (how many files, how large each one), using
 * the server's own numbers from GET /api/v1/files/limits. It never decides
 * whether a file is allowed -- that remains the server's alone, and unknown
 * limits simply mean no preflight.
 */

import { api, uploadFiles, ApiError, NetworkError } from './api.js';
import { toastError } from './toast.js';

let blockedExtensions = new Set();

/** {maxFilesPerRequest, maxFileSizeBytes} once the server answers; null until then. */
let limits = null;

export function setBlockedExtensions(next) {
  blockedExtensions = next;
}

/** Mirrors FilenameAnalyzer: every segment of the chain, not just the last. */
function extensionChain(filename) {
  const name = filename.replace(/[\\/]/g, '/').split('/').pop().replace(/[. \t]+$/, '');
  const segments = name.split('.');
  return segments.slice(1).filter(Boolean).map((s) => s.toLowerCase());
}

function looksBlocked(filename) {
  return extensionChain(filename).some((segment) => blockedExtensions.has(segment));
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

/* ------------------------------- limits -------------------------------- */

/**
 * Asks the server what shape of request it accepts, and says so on screen.
 *
 * A failure here is not worth interrupting anyone: the upload path still works
 * and the server still enforces every limit. The screen just stops promising
 * numbers it cannot vouch for, and the preflight stays off.
 */
async function loadLimits() {
  const hint = document.getElementById('dropzone-limits');
  try {
    limits = await api.getUploadLimits();
    const megabytes = Math.round(limits.maxFileSizeBytes / 1024 / 1024);
    hint.textContent = `한 번에 최대 ${limits.maxFilesPerRequest}개 · 파일당 최대 ${megabytes}MB`;
  } catch {
    limits = null;
    hint.textContent = '업로드 한도를 불러오지 못했습니다. 제한은 서버가 적용합니다.';
  }
}

/**
 * @returns the reasons this request cannot be sent as selected, or null when
 *          the shape is fine or the limits are unknown.
 */
function preflight(files) {
  if (!limits) return null;

  const reasons = [];
  if (files.length > limits.maxFilesPerRequest) {
    reasons.push(`${files.length}개를 선택했습니다. 한 번에 최대 ${limits.maxFilesPerRequest}개까지 보낼 수 있습니다.`);
  }

  const oversized = files.filter((file) => file.size > limits.maxFileSizeBytes);
  if (oversized.length > 0) {
    const megabytes = Math.round(limits.maxFileSizeBytes / 1024 / 1024);
    const names = oversized.map((file) => `${file.name} (${formatBytes(file.size)})`).join(', ');
    reasons.push(`파일당 최대 ${megabytes}MB입니다. 초과: ${names}`);
  }

  return reasons.length > 0 ? reasons : null;
}

/* ------------------------- request-level display ------------------------ */

function showRequestError(message, detail, code) {
  const banner = document.getElementById('upload-error');
  const detailNode = document.getElementById('upload-error-detail');
  const codeNode = document.getElementById('upload-error-code');

  document.getElementById('upload-error-text').textContent = message;

  detailNode.textContent = detail || '';
  detailNode.hidden = !detail;

  codeNode.textContent = code || '';
  codeNode.hidden = !code;

  banner.hidden = false;
}

function hideRequestError() {
  document.getElementById('upload-error').hidden = true;
}

/** One bar for the one request, labelled with what it is actually carrying. */
function startRequestProgress(files) {
  const container = document.getElementById('upload-progress');
  const bar = document.getElementById('upload-progress-bar');
  const percent = document.getElementById('upload-progress-percent');
  const totalBytes = files.reduce((sum, file) => sum + file.size, 0);

  document.getElementById('upload-progress-label').textContent =
    `${files.length}개 파일 · ${formatBytes(totalBytes)} 전송 중`;
  bar.style.width = '0%';
  percent.textContent = '0%';
  container.hidden = false;

  return {
    set(value) {
      bar.style.width = `${value}%`;
      percent.textContent = `${value}%`;
    },
    finish() {
      container.hidden = true;
    },
  };
}

/* ------------------------------ rendering ------------------------------ */

function createRow(file) {
  const li = document.createElement('li');
  li.className = 'result is-pending';

  const head = document.createElement('div');
  head.className = 'result-head';

  const name = document.createElement('span');
  name.className = 'result-name';
  name.textContent = `${file.name} (${formatBytes(file.size)})`;

  const status = document.createElement('span');
  status.className = 'result-status';
  // No per-row progress: the row is waiting for the batch's answer, and saying
  // "업로드 중" next to a bar of its own would claim a fate it does not have.
  status.textContent = looksBlocked(file.name) ? '차단 예상 · 대기 중' : '대기 중';

  head.append(name, status);
  li.append(head);
  return { li, status };
}

function applyVerdict(row, result) {
  const accepted = result.status === 'ACCEPTED';
  row.li.className = `result ${accepted ? 'is-accepted' : 'is-rejected'}`;
  row.status.textContent = accepted ? '업로드 성공' : '차단됨';

  if (accepted) return;

  // Requirement B: refuse with a clear reason. The message is the headline, the
  // detail is the evidence, the code is for reporting a problem.
  const message = document.createElement('p');
  message.className = 'result-message';
  message.textContent = result.message || '업로드가 거부되었습니다.';
  row.li.appendChild(message);

  if (result.detail) {
    const detail = document.createElement('p');
    detail.className = 'result-detail';
    detail.textContent = result.detail;
    row.li.appendChild(detail);
  }

  if (result.code) {
    const code = document.createElement('span');
    code.className = 'result-code';
    code.textContent = result.code;
    row.li.appendChild(code);
  }
}

/**
 * The request failed, so no file in it was judged. The rows say that and
 * nothing more -- the reason is the request's and is shown once, in the banner.
 */
function markRowsSkipped(rows) {
  for (const row of rows) {
    row.li.className = 'result is-skipped';
    row.status.textContent = '전송되지 않음';
  }
}

/* ------------------------------- uploading ----------------------------- */

async function send(fileList) {
  const files = Array.from(fileList);
  if (files.length === 0) return;

  hideRequestError();

  const reasons = preflight(files);
  if (reasons) {
    const message = '업로드 요청을 보내지 않았습니다. 선택을 줄인 뒤 다시 시도해 주세요.';
    showRequestError(message, reasons.join(' '), null);
    toastError(message);
    return;
  }

  const list = document.getElementById('upload-results');
  const rows = files.map((file) => {
    const row = createRow(file);
    list.prepend(row.li);
    return row;
  });

  const progress = startRequestProgress(files);
  try {
    const response = await uploadFiles(files, { onProgress: progress.set });

    // Results come back in submission order.
    response.results.forEach((result, index) => {
      if (rows[index]) applyVerdict(rows[index], result);
    });
    // A row with no verdict was never judged; it must not keep saying 대기 중.
    markRowsSkipped(rows.slice(response.results.length));
  } catch (error) {
    const described = error instanceof ApiError;
    const message = described || error instanceof NetworkError
      ? error.message
      : '업로드 중 오류가 발생했습니다.';
    showRequestError(message, described ? error.detail : null, described ? error.code : null);
    markRowsSkipped(rows);
    toastError(message);
  } finally {
    progress.finish();
  }
}

/* --------------------------------- init -------------------------------- */

export function initUpload() {
  const dropzone = document.getElementById('dropzone');
  const input = document.getElementById('file-input');

  loadLimits();

  dropzone.addEventListener('click', () => input.click());
  dropzone.addEventListener('keydown', (event) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      input.click();
    }
  });

  input.addEventListener('change', () => {
    send(input.files);
    input.value = ''; // allow re-selecting the same file
  });

  // dragover must be cancelled or the browser navigates to the dropped file.
  for (const type of ['dragenter', 'dragover']) {
    dropzone.addEventListener(type, (event) => {
      event.preventDefault();
      dropzone.classList.add('is-dragover');
    });
  }
  for (const type of ['dragleave', 'drop']) {
    dropzone.addEventListener(type, (event) => {
      event.preventDefault();
      dropzone.classList.remove('is-dragover');
    });
  }
  dropzone.addEventListener('drop', (event) => {
    if (event.dataTransfer?.files?.length) send(event.dataTransfer.files);
  });

  // Dropping anywhere else should not replace the page.
  window.addEventListener('dragover', (event) => event.preventDefault());
  window.addEventListener('drop', (event) => event.preventDefault());
}
