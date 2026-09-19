/**
 * Upload screen.
 *
 * A deliberate choice: even when the client already knows an extension is
 * blocked, the file is still sent. The verdict shown is always the server's, so
 * the screen demonstrates that the policy is enforced server-side rather than
 * merely reflecting what the client believes. The local blocklist is used only
 * to label a row as expected-to-be-blocked while it uploads.
 */

import { uploadFiles, ApiError, NetworkError } from './api.js';
import { toastError } from './toast.js';

let blockedExtensions = new Set();

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
  status.textContent = looksBlocked(file.name) ? '차단 예상 · 전송 중' : '업로드 중';

  head.append(name, status);

  const progress = document.createElement('div');
  progress.className = 'progress';
  const bar = document.createElement('div');
  bar.className = 'progress-bar';
  progress.appendChild(bar);

  li.append(head, progress);
  return { li, status, bar, progress };
}

function applyVerdict(row, result) {
  const accepted = result.status === 'ACCEPTED';
  row.li.className = `result ${accepted ? 'is-accepted' : 'is-rejected'}`;
  row.status.textContent = accepted ? '업로드 성공' : '차단됨';
  row.progress.remove();

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

function markRowsFailed(rows, message) {
  for (const row of rows) {
    row.li.className = 'result is-rejected';
    row.status.textContent = '전송 실패';
    row.progress.remove();

    const text = document.createElement('p');
    text.className = 'result-message';
    text.textContent = message;
    row.li.appendChild(text);
  }
}

/* ------------------------------- uploading ----------------------------- */

async function send(fileList) {
  const files = Array.from(fileList);
  if (files.length === 0) return;

  const list = document.getElementById('upload-results');
  const rows = files.map((file) => {
    const row = createRow(file);
    list.prepend(row.li);
    return row;
  });

  try {
    const response = await uploadFiles(files, {
      onProgress: (percent) => rows.forEach((row) => {
        row.bar.style.width = `${percent}%`;
      }),
    });

    // Results come back in submission order.
    response.results.forEach((result, index) => {
      if (rows[index]) applyVerdict(rows[index], result);
    });
  } catch (error) {
    const message = error instanceof NetworkError || error instanceof ApiError
      ? error.message
      : '업로드 중 오류가 발생했습니다.';
    markRowsFailed(rows, message);
    toastError(message);
  }
}

/* --------------------------------- init -------------------------------- */

export function initUpload() {
  const dropzone = document.getElementById('dropzone');
  const input = document.getElementById('file-input');

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
