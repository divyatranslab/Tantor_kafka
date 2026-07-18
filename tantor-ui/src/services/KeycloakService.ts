import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL || 'https://keycloak.tantor.io',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'Gatekeeper',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'apb-kafka',
});

export const isAuthEnabled = () => import.meta.env.VITE_AUTH_ENABLED === 'true';

let initializationPromise: Promise<boolean> | undefined;
let authenticatedFetchInstalled = false;
let nativeFetch: typeof window.fetch | undefined;

const currentRedirectUri = () => window.location.href;

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

export const logout = () =>
  isAuthEnabled()
    ? keycloak.logout({
        redirectUri: window.location.origin,
      })
    : Promise.resolve();

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
  if (authenticatedFetchInstalled) return;

  nativeFetch = window.fetch.bind(window);
  authenticatedFetchInstalled = true;

  window.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : undefined;
    const url = new URL(request?.url || input.toString(), window.location.origin);
    const headers = new Headers(init?.headers || request?.headers);

    if (url.origin === window.location.origin && url.pathname.startsWith('/api/')) {
      if (isAuthEnabled()) {
        const token = await getValidToken();
        if (token) {
          headers.set('Authorization', `Bearer ${token}`);
        }
      } else {
        // When auth is disabled locally, supply a mock administrative token header so backend RoleAuthenticationUtil can decode it
        // The mock token payload below corresponds to: {"preferred_username":"shaukat","roles":["admin"]}
        const mockJwt = "eyJhbGciOiJIUzI1NiJ9.eyJwcmVmZXJyZWRfdXNlcm5hbWUiOiJzaGF1a2F0Iiwicm9sZXMiOlsiYWRtaW4iXX0.mocksignature";
        headers.set('Authorization', `Bearer ${mockJwt}`);
      }
    }

    return nativeFetch!(input, {
      ...init,
      headers,
    });
  };
};
