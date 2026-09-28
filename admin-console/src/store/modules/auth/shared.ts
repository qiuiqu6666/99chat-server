const TOKEN_KEY = 'chat99_admin_token';

/** Get token */
export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || '';
}

/** Store access token */
export function setToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

/** Clear auth storage */
export function clearAuthStorage() {
  localStorage.removeItem(TOKEN_KEY);
}
