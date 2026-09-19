/**
 * Extension policy management screen.
 *
 * The checkbox interaction is optimistic with rollback: the box flips at once so
 * the UI feels immediate, and flips back if the server refuses. Without the
 * rollback the screen would quietly disagree with the database.
 */

import { api, ApiError, NetworkError } from './api.js';
import { toastError, toastSuccess } from './toast.js';

/** Mirrors the server's normalisation. Used for instant feedback only -- the
 *  server re-validates everything and its answer is the one that counts. */
const VALID_EXTENSION = /^[a-z0-9]{1,20}$/;

let state = { fixed: [], custom: [], limits: { maxCustomExtensions: 200, maxExtensionLength: 20 } };
const listeners = new Set();

export function onPolicyChange(listener) {
  listeners.add(listener);
}

function notify() {
  const blocked = new Set([
    ...state.fixed.filter((f) => f.blocked).map((f) => f.extension),
    ...state.custom.map((c) => c.extension),
  ]);
  listeners.forEach((listener) => listener(blocked));
}

/* ------------------------------- loading ------------------------------- */

export async function loadPolicy({ showSkeleton = false } = {}) {
  const errorBanner = document.getElementById('policy-error');
  if (showSkeleton) renderFixedSkeleton();

  try {
    state = await api.getPolicy();
    errorBanner.hidden = true;
    render();
    notify();
  } catch (error) {
    showLoadError(error);
  }
}

function showLoadError(error) {
  const banner = document.getElementById('policy-error');
  const text = document.getElementById('policy-error-text');
  text.textContent = error instanceof NetworkError
    ? error.message
    : `정책을 불러오지 못했습니다: ${error.message}`;
  banner.hidden = false;
  document.getElementById('fixed-list').innerHTML = '';
}

/* ------------------------------ rendering ------------------------------ */

function renderFixedSkeleton() {
  document.getElementById('fixed-list').innerHTML =
    '<div class="skeleton-row" aria-hidden="true"></div>';
}

function render() {
  renderFixed();
  renderCustom();
}

function renderFixed() {
  const container = document.getElementById('fixed-list');
  container.innerHTML = '';

  for (const item of state.fixed) {
    const label = document.createElement('label');
    label.className = `chip${item.blocked ? ' is-blocked' : ''}`;

    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = item.blocked;
    checkbox.dataset.extension = item.extension;
    checkbox.addEventListener('change', () => toggleFixed(item.extension, checkbox));

    const text = document.createElement('span');
    text.className = 'chip-label';
    text.textContent = item.extension;

    label.append(checkbox, text);
    container.appendChild(label);
  }
}

function renderCustom() {
  const list = document.getElementById('custom-list');
  const empty = document.getElementById('custom-empty');
  const counter = document.getElementById('custom-counter');
  const max = state.limits.maxCustomExtensions;

  list.innerHTML = '';
  empty.hidden = state.custom.length > 0;

  for (const item of state.custom) {
    const li = document.createElement('li');
    li.className = 'tag';

    const name = document.createElement('span');
    name.textContent = item.extension;

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'tag-remove';
    remove.textContent = '×';
    remove.setAttribute('aria-label', `${item.extension} 삭제`);
    remove.addEventListener('click', () => removeCustom(item.extension, li));

    li.append(name, remove);
    list.appendChild(li);
  }

  counter.textContent = `${state.custom.length} / ${max}`;
  counter.classList.toggle('is-at-limit', state.custom.length >= max);
  counter.classList.toggle('is-near-limit',
    state.custom.length >= max * 0.9 && state.custom.length < max);

  const input = document.getElementById('custom-input');
  const submit = document.getElementById('custom-submit');
  const atLimit = state.custom.length >= max;
  input.disabled = atLimit;
  submit.disabled = atLimit;
  input.placeholder = atLimit
    ? `최대 ${max}개에 도달했습니다`
    : '예: sh (점 없이, 최대 20자)';
}

/* ------------------------------- actions ------------------------------- */

async function toggleFixed(extension, checkbox) {
  const desired = checkbox.checked;
  const chip = checkbox.closest('.chip');

  // Optimistic: reflect the intent immediately...
  chip.classList.add('is-saving');
  chip.classList.toggle('is-blocked', desired);

  try {
    state = await api.setFixedBlocked(extension, desired);
    render();
    notify();
    toastSuccess(`${extension} 확장자를 ${desired ? '차단' : '허용'}했습니다.`);
  } catch (error) {
    // ...and roll back if the server disagreed, so the screen matches the DB.
    checkbox.checked = !desired;
    chip.classList.toggle('is-blocked', !desired);
    reportError(error, '정책을 변경하지 못했습니다');
  } finally {
    chip.classList.remove('is-saving');
  }
}

async function addCustom(event) {
  event.preventDefault();

  const input = document.getElementById('custom-input');
  const errorText = document.getElementById('custom-input-error');
  const submit = document.getElementById('custom-submit');
  const raw = input.value;

  const clientError = validateLocally(raw);
  if (clientError) {
    errorText.textContent = clientError;
    errorText.hidden = false;
    input.focus();
    return;
  }
  errorText.hidden = true;

  submit.disabled = true;
  try {
    state = await api.addCustom(raw.trim());
    render();
    notify();
    input.value = '';
    input.focus();
  } catch (error) {
    if (error instanceof ApiError) {
      errorText.textContent = error.message;
      errorText.hidden = false;
    } else {
      reportError(error, '확장자를 추가하지 못했습니다');
    }
  } finally {
    submit.disabled = false;
    renderCustom();
  }
}

async function removeCustom(extension, listItem) {
  listItem.classList.add('is-removing');
  try {
    state = await api.removeCustom(extension);
    render();
    notify();
  } catch (error) {
    listItem.classList.remove('is-removing');
    reportError(error, '확장자를 삭제하지 못했습니다');
  }
}

/**
 * Instant feedback for obvious mistakes. This is a convenience, not a control:
 * anything that slips past here is still rejected by the server.
 */
function validateLocally(raw) {
  const value = raw.trim().replace(/^\.+/, '').toLowerCase();
  const max = state.limits.maxExtensionLength;

  if (!value) return '확장자를 입력해 주세요.';
  if (value.includes('.')) return '확장자에는 점(.)을 포함할 수 없습니다.';
  if (/\s/.test(value)) return '확장자에는 공백을 포함할 수 없습니다.';
  if (value.length > max) return `확장자는 최대 ${max}자까지 입력할 수 있습니다.`;
  if (!VALID_EXTENSION.test(value)) return '확장자는 영문자와 숫자만 사용할 수 있습니다.';
  if (state.fixed.some((f) => f.extension === value)) {
    return `'${value}'는 고정 확장자입니다. 위 고정 확장자 영역에서 체크해 주세요.`;
  }
  if (state.custom.some((c) => c.extension === value)) {
    return `'${value}'는 이미 추가된 확장자입니다.`;
  }
  return null;
}

function reportError(error, prefix) {
  if (error instanceof NetworkError) {
    toastError(error.message);
  } else if (error instanceof ApiError && error.status === 401) {
    toastError('관리자 토큰이 필요합니다. 우측 상단에 토큰을 입력해 주세요.');
  } else {
    toastError(`${prefix}: ${error.message}`);
  }
}

/* --------------------------------- init -------------------------------- */

export function initPolicy() {
  document.getElementById('custom-form').addEventListener('submit', addCustom);
  document.getElementById('policy-refresh')
    .addEventListener('click', () => loadPolicy({ showSkeleton: true }));
  document.getElementById('policy-retry')
    .addEventListener('click', () => loadPolicy({ showSkeleton: true }));

  document.getElementById('custom-input').addEventListener('input', () => {
    document.getElementById('custom-input-error').hidden = true;
  });

  return loadPolicy({ showSkeleton: true });
}
