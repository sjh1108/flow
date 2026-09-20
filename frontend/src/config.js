/**
 * Runtime configuration.
 *
 * The API base comes from a <meta> tag rather than a build-time constant, so one
 * static bundle works in every environment and Vercel does not need a build step.
 * Vercel serves index.html exactly as committed, so that tag holds the deployed
 * API origin.
 *
 * Which means a page opened from a developer's own machine would talk to the
 * deployed API -- the README quick start would run a local backend that the
 * browser never calls, and the clicking-around it asks for would land on real
 * data. So a locally served page ignores the tag: it uses localhost:8080, or
 * whatever `?api=` names.
 *
 * `?api=` is honoured only for a locally served page, and that restriction is
 * the point rather than caution. The admin token lives in localStorage and is
 * sent to API_BASE as a header, so on the deployed origin a link carrying
 * `?api=https://somewhere-else` would hand that token to whoever sent the link.
 * Confining the override to pages served from this machine means such a link
 * cannot be aimed at anyone but yourself.
 */

const DEFAULT_API_BASE = 'http://localhost:8080';
const TOKEN_STORAGE_KEY = 'extguard.adminToken';

/** Is this page itself being served from the machine viewing it? */
function isLocallyServed() {
  const host = window.location.hostname;
  return host === 'localhost' || host === '127.0.0.1' || host === '::1' || host === '[::1]';
}

function readApiBase() {
  const trim = (value) => value?.trim().replace(/\/+$/, '') || '';

  if (isLocallyServed()) {
    // Deliberately not falling back to the tag: its value is the deployed API,
    // which is the thing a local page must not reach by accident.
    const override = trim(new URLSearchParams(window.location.search).get('api'));
    return override || DEFAULT_API_BASE;
  }

  const deployed = trim(document.querySelector('meta[name="api-base"]')?.getAttribute('content'));
  return deployed || DEFAULT_API_BASE;
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
