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
 * upload progress events and per-file progress is worth the older API.
 *
 * Resolves for both 200 and 422: a rejection is a normal, expected outcome whose
 * body carries the per-file verdicts, not a transport failure.
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
      // 200 = at least one accepted, 422 = all rejected. Both carry results[].
      if (xhr.status === 200 || xhr.status === 422) {
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
