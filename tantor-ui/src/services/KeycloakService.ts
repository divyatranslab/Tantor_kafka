import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'https://keycloak.tantor.io',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'Gatekeeper',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'apb-kafka',
});

let initializationPromise: Promise<boolean> | undefined;
let authenticatedFetchInstalled = false;
let nativeFetch: typeof window.fetch | undefined;

const dashboardRedirectUri = () => `${window.location.origin}/dashboard`;

export const initKeycloak = (): Promise<boolean> => {
  if (!initializationPromise) {
    initializationPromise = keycloak.init({
      onLoad: 'login-required',
      pkceMethod: 'S256',
      checkLoginIframe: true,
      redirectUri: dashboardRedirectUri(),
    });
  }

  return initializationPromise;
};

export const login = () =>
  keycloak.login({
    redirectUri: dashboardRedirectUri(),
  });

export const logout = () =>
  keycloak.logout({
    redirectUri: window.location.origin,
  });

export const getToken = () => keycloak.token;

export const getKeycloak = () => keycloak;

export const getValidToken = async () => {
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
  if (authenticatedFetchInstalled) return;

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
