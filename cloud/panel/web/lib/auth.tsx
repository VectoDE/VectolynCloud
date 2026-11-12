// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, setCsrf, type PanelInfo, type Session } from "@/lib/api";

interface Auth {
  /** null while loading or when the server is down */
  info: PanelInfo | null;
  /** null when signed out */
  session: Session | null;
  loading: boolean;
  down: boolean;
  refresh: () => Promise<void>;
  signOut: () => Promise<void>;
  can: (right: string) => boolean;
}

const AuthContext = createContext<Auth | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [info, setInfo] = useState<PanelInfo | null>(null);
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);
  const [down, setDown] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const next = await api<PanelInfo>("/api/panel/info");
      setInfo(next);
      setDown(false);
      const current = await api<Session | { user: null }>("/api/panel/session");
      if (current.user === null) {
        setSession(null);
        setCsrf(null);
      } else {
        setSession(current as Session);
      }
    } catch {
      setDown(true);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const signOut = useCallback(async () => {
    try {
      await api("/api/panel/logout", { body: {} });
    } finally {
      setCsrf(null);
      setSession(null);
      window.location.assign("/login/");
    }
  }, []);

  const can = useCallback((right: string) => session?.rights.includes(right) ?? false, [session]);

  return (
    <AuthContext.Provider value={{ info, session, loading, down, refresh, signOut, can }}>{children}</AuthContext.Provider>
  );
}

export function useAuth(): Auth {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth outside of AuthProvider");
  return value;
}

/**
 * Sends the browser where it belongs: to the setup before the first account, to the sign-in
 * without a session. Returns true while the page should render nothing.
 */
export function useGuard(page: "setup" | "login" | "private"): boolean {
  const { info, session, loading } = useAuth();
  const target =
    loading || !info
      ? null
      : info["setup-required"]
        ? page === "setup" ? null : "/setup/"
        : page === "setup"
          ? "/login/"
          : page === "private" && !session
            ? "/login/"
            : page === "login" && session
              ? "/"
              : null;
  useEffect(() => {
    if (target) window.location.replace(target);
  }, [target]);
  return loading || target !== null;
}
