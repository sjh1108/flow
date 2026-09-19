/**
 * Runtime configuration.
 *
 * The API base comes from a <meta> tag rather than a build-time constant, so one
 * static bundle works in every environment and Vercel does not need a build step.
 */

const DEFAULT_API_BASE = 'http://localhost:8080';
const TOKEN_STORAGE_KEY = 'extguard.adminToken';

function readApiBase() {
  const tag = document.querySelector('meta[name="api-base"]');
  const value = tag?.getAttribute('content')?.trim();
  return (value || DEFAULT_API_BASE).replace(/\/+$/, '');
}

export const API_BASE = readApiBase();

/**
 * The admin token lives in localStorage only. It never leaves the browser except
 * as a request header to the configured API.
 *
 * Storage access can throw in private browsing or with site data blocked, so
 * every read and write is guarded and the app stays usable without it.
 */
export function getAdminToken() {
  try {
    return localStorage.getItem(TOKEN_STORAGE_KEY) || '';
  } catch {
    return '';
  }
}

export function setAdminToken(token) {
  try {
    if (token) {
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
    } else {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
    }
  } catch {
    /* Non-fatal: the token simply will not persist across reloads. */
  }
}
