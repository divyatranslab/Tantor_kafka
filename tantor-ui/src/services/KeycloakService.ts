import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'https://keycloak.tantor.io',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'Gatekeeper',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'apb-kafka',
});

export const isAuthEnabled = () => import.meta.env.VITE_AUTH_ENABLED !== 'false';

let initializationPromise: Promise<boolean> | undefined;
let authenticatedFetchInstalled = false;
let nativeFetch: typeof window.fetch | undefined;

const currentRedirectUri = () => window.location.href;
const postLogoutRedirectUri = () => new URL(import.meta.env.BASE_URL || '/', window.location.origin).href;

export const initKeycloak = (): Promise<boolean> => {
  if (!isAuthEnabled()) {
    return Promise.resolve(true);
  }

  if (!initializationPromise) {
    initializationPromise = keycloak.init({
      onLoad: 'login-required',
      pkceMethod: 'S256',
      checkLoginIframe: true,
      redirectUri: currentRedirectUri(),
    });
  }

  return initializationPromise;
};

export const login = () =>
  isAuthEnabled()
    ? keycloak.login({
        redirectUri: currentRedirectUri(),
      })
    : Promise.resolve();

export const logout = () => {
  const redirectUri = postLogoutRedirectUri();
  if (!isAuthEnabled()) {
    window.location.assign(redirectUri);
    return Promise.resolve();
  }

  // Navigate directly so logout remains tied to the user's click and cannot
  // be swallowed by an unresolved adapter promise.
  window.location.assign(keycloak.createLogoutUrl({ redirectUri, logoutMethod: 'GET' }));
  return Promise.resolve();
};

export const getToken = () => isAuthEnabled() ? keycloak.token : undefined;

export const getKeycloak = () => keycloak;

export const getValidToken = async () => {
  if (!isAuthEnabled()) {
    return undefined;
  }

  if (!keycloak.authenticated) {
    await login();
    return undefined;
  }

  try {
    await keycloak.updateToken(30);
    return keycloak.token;
  } catch {
    await login();
    return undefined;
  }
};

export const installAuthenticatedFetch = () => {
  if (!isAuthEnabled() || authenticatedFetchInstalled) return;

  nativeFetch = window.fetch.bind(window);
  authenticatedFetchInstalled = true;

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : undefined;
    const url = new URL(request?.url || input.toString(), window.location.origin);
    const headers = new Headers(init?.headers || request?.headers);

    if (url.origin === window.location.origin && url.pathname.startsWith('/api/')) {
      const token = await getValidToken();
      if (token) {
        headers.set('Authorization', `Bearer ${token}`);
      }
    }

    return nativeFetch!(input, {
      ...init,
      headers,
    });
  };
};
