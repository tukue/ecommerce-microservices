import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";

import { SESSION_EXPIRED } from "../../../shared/http/session-expired";
import type { Session } from "../domain/session";
import {
  beginLogin as beginProviderLogin,
  getSession,
  login as apiLogin,
  logout as apiLogout,
} from "../api/auth-api";

type AuthContextValue = {
  readonly session: Session;
  readonly loading: boolean;
  readonly beginLogin: (returnTo?: string) => void;
  readonly login: (username: string, password: string) => Promise<void>;
  readonly logout: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const requestVersion = useRef(0);
  const [session, setSession] = useState<Session>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const expire = () => {
      requestVersion.current++;
      setSession(null);
      setLoading(false);
    };
    window.addEventListener(SESSION_EXPIRED, expire);
    return () => window.removeEventListener(SESSION_EXPIRED, expire);
  }, []);

  useEffect(() => {
    let active = true;
    const version = requestVersion.current;
    getSession()
      .then((value) => {
        if (active && version === requestVersion.current) setSession(value);
      })
      .catch(() => {
        if (active && version === requestVersion.current) setSession(null);
      })
      .finally(() => {
        if (active && version === requestVersion.current) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const version = ++requestVersion.current;
    try {
      const nextSession = await apiLogin(username, password);
      if (version === requestVersion.current) setSession(nextSession);
    } finally {
      if (version === requestVersion.current) setLoading(false);
    }
  }, []);

  const beginLogin = useCallback((returnTo?: string) => {
    beginProviderLogin(returnTo);
  }, []);

  const logout = useCallback(async () => {
    requestVersion.current++;
    await apiLogout();
    setSession(null);
  }, []);

  return (
    <AuthContext.Provider
      value={{ session, loading, beginLogin, login, logout }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useSession(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useSession must be used within AuthProvider");
  return ctx;
}
