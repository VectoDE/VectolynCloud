// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { useAuth } from "@/lib/auth";
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

function NavLink({ href, path, children }: { href: string; path: string; children: ReactNode }) {
  const current = path === href;
  return (
    <a href={href} aria-current={current ? "page" : undefined}
      className={`flex min-h-11 items-center text-ink underline-offset-4 ${current ? "font-bold underline" : "hover:underline"}`}>
      {children}
    </a>
  );
}

export function Shell({ children }: { children: ReactNode }) {
  const { t } = useI18n();
  const { session, can, signOut } = useAuth();
  const [path, setPath] = useState("/");
  useEffect(() => setPath(window.location.pathname), []);
  // Known when the page is built: every page but sign-in and setup has the navigation row. Its
  // height is kept free from the first paint, so nothing moves once the session has loaded.
  const route = (usePathname() ?? "/").replace(/\/+$/, "");
  const publicPage = route === "/login" || route === "/setup";
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
          <div className="flex flex-wrap items-center gap-4">
            {session && (
              <button type="button" onClick={() => void signOut()}
                className="min-h-11 border-2 border-line bg-raised px-3 font-bold text-ink">
                {t("nav.sign-out")}
              </button>
            )}
            <LanguageSwitch />
          </div>
        </div>
        {!publicPage && (
          <nav aria-label={t("nav.label")} className="mx-auto max-w-6xl px-4 pb-2">
            <ul className="flex min-h-11 items-center gap-x-6 overflow-x-auto whitespace-nowrap">
              {session && (
                <>
                  <li><NavLink href="/" path={path}>{t("nav.overview")}</NavLink></li>
                  {can("services:read") && <li><NavLink href="/services/" path={path}>{t("nav.services")}</NavLink></li>}
                  {can("services:read") && <li><NavLink href="/players/" path={path}>{t("nav.players")}</NavLink></li>}
                  {(can("config:read") || can("templates:read")) && <li><NavLink href="/config/" path={path}>{t("nav.config")}</NavLink></li>}
                  {(can("node:read") || can("audit:read") || can("admin")) && <li><NavLink href="/activity/" path={path}>{t("nav.activity")}</NavLink></li>}
                  {can("users:manage") && <li><NavLink href="/users/" path={path}>{t("nav.users")}</NavLink></li>}
                  <li><NavLink href="/account/" path={path}>{t("nav.account")}</NavLink></li>
                </>
              )}
            </ul>
          </nav>
        )}
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
