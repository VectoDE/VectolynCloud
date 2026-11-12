// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import type { ReactNode } from "react";
import { useI18n, type Locale } from "@/lib/i18n";

/** The mark in the colours of the current scheme; decorative next to the visible product name. */
function Mark() {
  return (
    <picture>
      <source srcSet="/mark-on-dark.svg" media="(prefers-color-scheme: dark)" />
      <img src="/mark-on-light.svg" alt="" width={28} height={28} className="block [image-rendering:pixelated]" />
    </picture>
  );
}

function LanguageSwitch() {
  const { locale, setLocale, t } = useI18n();
  return (
    <label className="flex items-center gap-2 text-sm text-muted">
      <span>{t("lang.label")}</span>
      <select
        name="locale"
        value={locale}
        onChange={(event) => setLocale(event.target.value as Locale)}
        className="min-h-11 border border-line bg-sunken px-2 text-ink"
      >
        <option value="de" lang="de">{t("lang.de")}</option>
        <option value="en" lang="en">{t("lang.en")}</option>
      </select>
    </label>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  return (
    <div className="flex min-h-screen flex-col">
      <a
        href="#content"
        className="absolute left-4 -top-20 z-10 bg-accent px-3 py-2 font-bold text-surface focus:top-2"
      >
        {t("a11y.skip")}
      </a>
      <header className="border-b border-line bg-raised">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-4 px-4 py-3">
          <a href="/" className="flex min-h-11 items-center gap-3 text-ink no-underline">
            <Mark />
            <span className="font-display text-xl tracking-normal">
              {t("app.name")} <span className="text-primary">{t("app.panel")}</span>
            </span>
          </a>
          <nav aria-label={t("nav.label")} className="flex flex-wrap items-center gap-4">
            <a href="/" aria-current="page" className="flex min-h-11 items-center text-ink underline underline-offset-4">
              {t("nav.overview")}
            </a>
            <LanguageSwitch />
          </nav>
        </div>
      </header>
      <main id="content" tabIndex={-1} className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        {children}
      </main>
      <footer className="border-t border-line text-sm text-muted">
        <div className="mx-auto flex max-w-6xl flex-wrap gap-6 px-4 py-4">
          <a className="text-primary underline underline-offset-4" href="https://github.com/VectoDE/VectolynCloud/blob/main/docs/cloud/panel.md">
            {t("footer.docs")}
          </a>
          <a className="text-primary underline underline-offset-4" href="https://github.com/VectoDE/VectolynCloud">
            {t("footer.source")}
          </a>
        </div>
      </footer>
    </div>
  );
}
