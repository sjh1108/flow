/**
 * Thin API client.
 *
 * Network failure and HTTP error are deliberately different types: "서버에 연결할
 * 수 없습니다" and "확장자가 중복입니다" call for different UI, and collapsing them
 * into one Error loses that.
 */

import { API_BASE, getAdminToken } from './config.js';

/** An error the server described: carries the stable code and Korean message. */
export class ApiError extends Error {
  constructor({ code, message, detail, status }) {
    super(message || '요청을 처리하지 못했습니다.');
    this.name = 'ApiError';
    this.code = code || 'UNKNOWN';
    this.detail = detail || null;
    this.status = status;
  }
}

/** The request never reached the server, or the response was unintelligible. */
export class NetworkError extends Error {
  constructor(message = '서버에 연결할 수 없습니다. 네트워크 상태를 확인해 주세요.') {
    super(message);
    this.name = 'NetworkError';
  }
}

async function request(path, { method = 'GET', body, admin = false } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (admin) {
    const token = getAdminToken();
    if (token) headers['X-Admin-Token'] = token;
  }

  let response;
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new NetworkError();
  }

  if (response.status === 204) return null;

  let payload = null;
  try {
    payload = await response.json();
  } catch {
    if (response.ok) throw new NetworkError('서버 응답을 해석할 수 없습니다.');
  }

  if (!response.ok) {
    throw new ApiError({ ...(payload || {}), status: response.status });
  }
  return payload;
}

export const api = {
  getPolicy: () => request('/api/v1/policy/extensions'),

  // The request-shape limits (how many files, how large each one). Published by
  // the server so the upload screen states the real numbers instead of literals
  // that would keep saying 10 and 20MB after the configuration changed.
  getUploadLimits: () => request('/api/v1/files/limits'),

  setFixedBlocked: (extension, blocked) =>
    request(`/api/v1/policy/extensions/fixed/${encodeURIComponent(extension)}`, {
      method: 'PATCH',
      body: { blocked },
      admin: true,
    }),

  addCustom: (extension) =>
    request('/api/v1/policy/extensions/custom', {
      method: 'POST',
      body: { extension },
      admin: true,
    }),

  removeCustom: (extension) =>
    request(`/api/v1/policy/extensions/custom/${encodeURIComponent(extension)}`, {
      method: 'DELETE',
      admin: true,
    }),
};

/**
 * Uploads via XMLHttpRequest rather than fetch, because fetch still has no
 * upload progress events. The progress reported here is the request's, not any
 * one file's -- every file travels in a single multipart body, so there is no
 * per-file figure to report and the screen must not draw one.
 *
 * Resolves whenever the body carries per-file verdicts, whatever the status: a
 * rejection is a normal, expected outcome, not a transport failure.
 *
 * Keyed on the body rather than on a list of status codes because the status can
 * change without the contract changing. It already did once -- a capacity refusal
 * became 507 while this function still listed only 200 and 422, so every file in
 * the batch was shown as "전송 실패" and the per-file reasons the server had
 * carefully filled in were thrown away.
 */
export function uploadFiles(files, { onProgress } = {}) {
  return new Promise((resolve, reject) => {
    const form = new FormData();
    for (const file of files) form.append('files', file, file.name);

    const xhr = new XMLHttpRequest();
    xhr.open('POST', `${API_BASE}/api/v1/files`);

    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable && onProgress) {
        onProgress(Math.round((event.loaded / event.total) * 100));
      }
    });

    xhr.addEventListener('load', () => {
      let payload = null;
      try {
        payload = JSON.parse(xhr.responseText);
      } catch {
        reject(new NetworkError('서버 응답을 해석할 수 없습니다.'));
        return;
      }
      // A batch verdict carries results[]; a request-level failure (no file
      // part, too many files) carries {code, message} instead and is an error.
      if (Array.isArray(payload?.results)) {
        resolve(payload);
      } else {
        reject(new ApiError({ ...payload, status: xhr.status }));
      }
    });

    xhr.addEventListener('error', () => reject(new NetworkError()));
    xhr.addEventListener('abort', () => reject(new NetworkError('업로드가 중단되었습니다.')));

    xhr.send(form);
  });
}
