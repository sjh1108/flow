/**
 * Entry point: wires the policy screen to the upload screen and handles the
 * cross-cutting bits (admin token field, offline detection).
 */

import { getAdminToken, setAdminToken } from './config.js';
import { initPolicy, onPolicyChange, loadPolicy } from './policy.js';
import { initUpload, setBlockedExtensions } from './upload.js';
import { toast, toastError } from './toast.js';

function initAdminTokenField() {
  const input = document.getElementById('admin-token-input');
  input.value = getAdminToken();
  input.addEventListener('change', () => {
    setAdminToken(input.value.trim());
    toast(input.value.trim() ? '관리자 토큰을 저장했습니다.' : '관리자 토큰을 삭제했습니다.');
  });
}

function initConnectivityHandling() {
  window.addEventListener('offline', () =>
    toastError('오프라인 상태입니다. 연결이 복구되면 다시 시도해 주세요.'));
  window.addEventListener('online', () => {
    toast('연결이 복구되었습니다. 정책을 다시 불러옵니다.');
    loadPolicy();
  });
}

async function start() {
  initAdminTokenField();
  initConnectivityHandling();
  initUpload();

  // Keep the upload screen's local hint in step with the real policy.
  onPolicyChange(setBlockedExtensions);

  await initPolicy();
}

start();
