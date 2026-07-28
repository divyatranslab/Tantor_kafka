import Keycloak from 'keycloak-js';

const keycloak = new Keycloak({
  // Vite replaces these values at build time. Keep the production SSO URL as
  // a safe default so a server-side build without a copied .env file cannot
  // generate redirects through "/undefined/protocol/openid-connect".
  url: import.meta.env.VITE_KEYCLOAK_URL || 'https://keycloak.tantor.io',
  realm: import.meta.env.VITE_KEYCLOAK_REALM || 'Gatekeeper',
  clientId: import.meta.env.VITE_KEYCLOAK_CLIENT_ID || 'apb-kafka',
});

export const isAuthEnabled = () => import.meta.env.PROD || import.meta.env.VITE_AUTH_ENABLED === 'true';

let initializationPromise: Promise<boolean> | undefined;

// Always return from SSO through the SPA root. Reusing the full current URL
// can perpetuate a malformed authentication path after configuration errors.
const currentRedirectUri = () => `${window.location.origin}/`;

export const initKeycloak = (): Promise<boolean> => {
  if (!isAuthEnabled()) {
    return Promise.resolve(true);
  }

  if (!initializationPromise) {
    initializationPromise = keycloak.init({
      onLoad: 'login-required',
      pkceMethod: 'S256',
      checkLoginIframe: false,
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
  if (!isAuthEnabled()) {
    return Promise.resolve();
  }

  // Reset the cached init promise so a fresh init happens when the app
  // re-mounts after the Keycloak redirect.  Without this the stale promise
  // resolves immediately with `authenticated = true` from the previous session.
  initializationPromise = undefined;

  return keycloak.logout({
    redirectUri: window.location.origin,
  });
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
