/**
 * Transient messages, announced to screen readers via the aria-live container
 * in index.html.
 */

const DISPLAY_MS = 4200;

export function toast(message, variant = 'info') {
  const stack = document.getElementById('toasts');
  if (!stack) return;

  const element = document.createElement('div');
  element.className = `toast is-${variant}`;
  element.textContent = message;
  stack.appendChild(element);

  setTimeout(() => element.remove(), DISPLAY_MS);
}

export const toastError = (message) => toast(message, 'error');
export const toastSuccess = (message) => toast(message, 'success');
