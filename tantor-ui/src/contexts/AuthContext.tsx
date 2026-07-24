import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { KeycloakTokenParsed } from 'keycloak-js';
import {
  getKeycloak,
  getToken,
  initKeycloak,
  isAuthEnabled,
  login,
  logout as keycloakLogout,
} from '../services/KeycloakService';

type DecodedToken = KeycloakTokenParsed & {
  name?: string;
  given_name?: string;
  family_name?: string;
  preferred_username?: string;
  email?: string;
  sid?: string;
  auth_time?: number;
  role?: string;
  roles?: string[];
  realm_access?: { roles?: string[] };
  resource_access?: Record<string, { roles?: string[] }>;
  groups?: string[];
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

const devDecodedToken = (): DecodedToken => {
  const role = import.meta.env.VITE_DEV_ROLE || 'monitor';
  return {
    preferred_username: import.meta.env.VITE_DEV_USER || 'shaukat',
    role,
    roles: [role],
  } as DecodedToken;
};

export function AuthProvider({ children }: { children: ReactNode }) {
  const authEnabled = isAuthEnabled();
  const [isInitializing, setIsInitializing] = useState(authEnabled);
  const [isAuthenticated, setIsAuthenticated] = useState(!authEnabled);
  const [accessToken, setAccessToken] = useState<string | undefined>();
  const [decodedToken, setDecodedToken] = useState<DecodedToken | undefined>(
    authEnabled ? undefined : devDecodedToken()
  );
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
    if (!authEnabled) {
      return true;
    }

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
  }, [authEnabled, clearAuthState, syncAuthState]);

  const logout = useCallback(async () => {
    if (!authEnabled) {
      return;
    }

    clearAuthState();
    await keycloakLogout();
  }, [authEnabled, clearAuthState]);

  useEffect(() => {
    if (!authEnabled) {
      setIsInitializing(false);
      setIsAuthenticated(true);
      setDecodedToken(devDecodedToken());
      return;
    }

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
  }, [authEnabled, clearAuthState, refreshToken, syncAuthState]);

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
