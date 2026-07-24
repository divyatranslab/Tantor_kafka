import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  url: import.meta.env.VITE_KEYCLOAK_URL,
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'Gatekeeper',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'apb-kafka',
});

export const isAuthEnabled = () => import.meta.env.PROD || import.meta.env.VITE_AUTH_ENABLED === 'true';

let initializationPromise: Promise<boolean> | undefined;

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

