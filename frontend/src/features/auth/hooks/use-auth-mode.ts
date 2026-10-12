import { useEffect, useState } from "react";

import { getAuthMode, type AuthMode } from "../api/auth-api";

/**
 * Resolve the active authentication mode. Defaults to the development form
 * until the server reports otherwise, so no protected content depends on it.
 */
export function useAuthMode(): AuthMode {
  const [mode, setMode] = useState<AuthMode>("password");

  useEffect(() => {
    let active = true;
    getAuthMode().then((value) => {
      if (active) setMode(value);
    });
    return () => {
      active = false;
    };
  }, []);

  return mode;
}
