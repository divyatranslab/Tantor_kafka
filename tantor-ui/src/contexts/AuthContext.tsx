import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { KeycloakTokenParsed } from 'keycloak-js';
import {
  getKeycloak,
  getToken,
  initKeycloak,
  installAuthenticatedFetch,
  login,
  logout as keycloakLogout,
} from '../services/KeycloakService';

type DecodedToken = KeycloakTokenParsed & {
  name?: string;
  preferred_username?: string;
  email?: string;
  sid?: string;
};

type AuthContextValue = {
  isInitializing: boolean;
  isAuthenticated: boolean;
  accessToken?: string;
  decodedToken?: DecodedToken;
  currentSessionId?: string;
  logout: () => Promise<void>;
  refreshToken: () => Promise<boolean>;
};

const AuthContext = createContext<AuthContextValue | undefined>(undefined);

const sessionIdFromToken = (token?: DecodedToken) => {
  const sessionState = token?.session_state;
  if (typeof sessionState === 'string') return sessionState;
  return token?.sid;
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const [isInitializing, setIsInitializing] = useState(true);
  const [isAuthenticated, setIsAuthenticated] = useState(false);
  const [accessToken, setAccessToken] = useState<string | undefined>();
  const [decodedToken, setDecodedToken] = useState<DecodedToken | undefined>();
  const [currentSessionId, setCurrentSessionId] = useState<string | undefined>();

  const syncAuthState = useCallback(() => {
    const keycloak = getKeycloak();
    const token = keycloak.tokenParsed as DecodedToken | undefined;
    setIsAuthenticated(Boolean(keycloak.authenticated));
    setAccessToken(getToken());
    setDecodedToken(token);
    setCurrentSessionId(sessionIdFromToken(token));
  }, []);

  const clearAuthState = useCallback(() => {
    setIsAuthenticated(false);
    setAccessToken(undefined);
    setDecodedToken(undefined);
    setCurrentSessionId(undefined);
  }, []);

  const refreshToken = useCallback(async () => {
    const keycloak = getKeycloak();
    try {
      const refreshed = await keycloak.updateToken(30);
      if (refreshed) {
        syncAuthState();
      }
      return true;
    } catch {
      clearAuthState();
      await login();
      return false;
    }
  }, [clearAuthState, syncAuthState]);

  const logout = useCallback(async () => {
    clearAuthState();
    await keycloakLogout();
  }, [clearAuthState]);

  useEffect(() => {
    let cancelled = false;
    let refreshTimer: number | undefined;
    const keycloak = getKeycloak();

    keycloak.onTokenExpired = () => {
      void refreshToken();
    };

    initKeycloak()
      .then(async authenticated => {
        if (cancelled) return;

        if (!authenticated) {
          clearAuthState();
          await login();
          return;
        }

        installAuthenticatedFetch();
        syncAuthState();
        refreshTimer = window.setInterval(() => {
          void refreshToken();
        }, 30000);
      })
      .catch(async () => {
        clearAuthState();
        await login();
      })
      .finally(() => {
        if (!cancelled) {
          setIsInitializing(false);
        }
      });

    return () => {
      cancelled = true;
      keycloak.onTokenExpired = undefined;
      if (refreshTimer) {
        window.clearInterval(refreshTimer);
      }
    };
  }, [clearAuthState, refreshToken, syncAuthState]);

  const value = useMemo<AuthContextValue>(
    () => ({
      isInitializing,
      isAuthenticated,
      accessToken,
      decodedToken,
      currentSessionId,
      logout,
      refreshToken,
    }),
    [accessToken, currentSessionId, decodedToken, isAuthenticated, isInitializing, logout, refreshToken],
  );

  if (isInitializing || !isAuthenticated) {
    return (
      <div className="auth-shell">
        <div className="auth-status">Connecting to Tantor SSO...</div>
      </div>
    );
  }

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used inside AuthProvider');
  }
  return context;
};
