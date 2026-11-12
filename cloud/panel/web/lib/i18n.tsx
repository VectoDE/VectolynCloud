// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import de from "@/messages/de.json";
import en from "@/messages/en.json";

export type Locale = "de" | "en";
export type MessageKey = keyof typeof en;

const catalogues: Record<Locale, Record<MessageKey, string>> = { de, en };
const STORAGE_KEY = "vectolyn.panel.locale";

/** The language of the browser: German for any de-* tag, English otherwise. */
function browserLocale(): Locale {
  const tags = typeof navigator === "undefined" ? [] : navigator.languages ?? [navigator.language];
  return tags.some((tag) => tag?.toLowerCase().startsWith("de")) ? "de" : "en";
}

function storedLocale(): Locale | null {
  try {
    const value = window.localStorage.getItem(STORAGE_KEY);
    return value === "de" || value === "en" ? value : null;
  } catch {
    return null;
  }
}

interface I18n {
  locale: Locale;
  setLocale: (locale: Locale) => void;
  t: (key: MessageKey, vars?: Record<string, string | number>) => string;
  formatNumber: (value: number, options?: Intl.NumberFormatOptions) => string;
  formatDate: (value: string | number | Date) => string;
}

const I18nContext = createContext<I18n | null>(null);

export function I18nProvider({ children }: { children: ReactNode }) {
  // The static HTML is rendered in English; the browser's language takes over on the first effect.
  const [locale, setLocaleState] = useState<Locale>("en");

  useEffect(() => {
    setLocaleState(storedLocale() ?? browserLocale());
  }, []);

  useEffect(() => {
    document.documentElement.lang = locale;
  }, [locale]);

  const setLocale = useCallback((next: Locale) => {
    setLocaleState(next);
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Storage switched off: the choice holds for this page only.
    }
  }, []);

  const value = useMemo<I18n>(() => {
    const messages = catalogues[locale];
    const numbers = new Intl.NumberFormat(locale);
    const dates = new Intl.DateTimeFormat(locale, { dateStyle: "medium", timeStyle: "medium" });
    return {
      locale,
      setLocale,
      t: (key, vars) => {
        const text = messages[key] ?? catalogues.en[key] ?? key;
        return vars ? text.replace(/\{(\w+)\}/g, (_, name: string) => String(vars[name] ?? `{${name}}`)) : text;
      },
      formatNumber: (n, options) => (options ? new Intl.NumberFormat(locale, options).format(n) : numbers.format(n)),
      formatDate: (d) => dates.format(new Date(d)),
    };
  }, [locale, setLocale]);

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}

export function useI18n(): I18n {
  const value = useContext(I18nContext);
  if (!value) throw new Error("useI18n outside of I18nProvider");
  return value;
}
