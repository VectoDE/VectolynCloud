// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useState, type FormEvent } from "react";
import { Button, Field, Notice, useErrorText } from "@/components/Form";
import { api } from "@/lib/api";
import { useGuard } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";

export default function SetupPage() {
  const { t } = useI18n();
  const errorText = useErrorText();
  const hidden = useGuard("setup");
  const [error, setError] = useState<string | null>(null);
  const [mismatch, setMismatch] = useState(false);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    const password = String(form.get("password") ?? "");
    if (password !== String(form.get("confirm") ?? "")) {
      setMismatch(true);
      return;
    }
    setMismatch(false);
    setBusy(true);
    setError(null);
    try {
      const name = String(form.get("name") ?? "").trim();
      await api("/api/panel/setup", {
        body: { token: String(form.get("token") ?? "").trim(), password, ...(name ? { name } : {}) },
      });
      window.location.assign("/account/");
    } catch (failure) {
      setError(errorText(failure));
      setBusy(false);
    }
  }

  if (hidden) return null;
  return (
    <section aria-labelledby="setup-title" className="max-w-xl">
      <h1 id="setup-title" className="font-display text-4xl">{t("setup.title")}</h1>
      <p className="mt-4">{t("setup.lede")}</p>
      <form onSubmit={submit} className="mt-6 flex flex-col gap-5" noValidate>
        {error && <Notice kind="error">{error}</Notice>}
        <Field label={t("setup.token")} hint={t("setup.token-hint")} name="token" required autoComplete="off"
          spellCheck={false} className="min-h-11 border border-line bg-sunken px-3 font-mono text-ink" />
        <Field label={t("setup.name")} hint={t("setup.name-hint")} name="name" autoComplete="username"
          maxLength={32} pattern="[A-Za-z0-9][A-Za-z0-9_.\-]{0,31}" />
        <Field label={t("setup.password")} hint={t("password.rules")} name="password" type="password" required
          autoComplete="new-password" minLength={12} maxLength={256} />
        <Field label={t("setup.confirm")} name="confirm" type="password" required autoComplete="new-password"
          error={mismatch ? t("password.mismatch") : null} />
        <Button type="submit" disabled={busy} className="self-start">{t("setup.submit")}</Button>
      </form>
    </section>
  );
}
