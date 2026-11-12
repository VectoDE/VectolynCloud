// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Button, Field, Notice, useErrorText } from "@/components/Form";
import { api, ApiError } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { getPasskey, passkeysSupported } from "@/lib/webauthn";

type Step = { kind: "password" } | { kind: "totp"; ticket: string };

export default function LoginPage() {
  const { t } = useI18n();
  const { info } = useAuth();
  const errorText = useErrorText();
  const hidden = useGuard("login");
  const [step, setStep] = useState<Step>({ kind: "password" });
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [passkeys, setPasskeys] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    setPasskeys(passkeysSupported());
    const code = new URLSearchParams(window.location.search).get("error");
    if (code && /^[a-z-]{1,40}$/.test(code)) setError(t(`error.${code}` as MessageKey));
  }, [t]);

  useEffect(() => {
    if (step.kind === "totp") codeRef.current?.focus();
  }, [step]);

  const done = () => window.location.assign("/");

  async function password(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      const reply = await api<{ "second-factor"?: string; ticket?: string }>("/api/panel/login", {
        body: { name: String(form.get("name") ?? "").trim(), password: String(form.get("password") ?? "") },
      });
      if (reply["second-factor"] === "totp" && reply.ticket) {
        setStep({ kind: "totp", ticket: reply.ticket });
        setBusy(false);
      } else {
        done();
      }
    } catch (failure) {
      setError(errorText(failure));
      setBusy(false);
    }
  }

  async function totp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (step.kind !== "totp") return;
    const form = new FormData(event.currentTarget);
    setBusy(true);
    setError(null);
    try {
      await api("/api/panel/login/totp", { body: { ticket: step.ticket, code: String(form.get("code") ?? "") } });
      done();
    } catch (failure) {
      setError(errorText(failure));
      if (failure instanceof ApiError && typeof failure.body.ticket === "string") {
        setStep({ kind: "totp", ticket: failure.body.ticket });
      } else {
        setStep({ kind: "password" });
      }
      setBusy(false);
    }
  }

  async function passkey() {
    setBusy(true);
    setError(null);
    try {
      const options = await api<{ ticket: string; challenge: string; "rp-id": string; timeout: number }>(
        "/api/panel/passkey/options", { body: {} });
      await api("/api/panel/passkey/login", { body: await getPasskey(options) });
      done();
    } catch (failure) {
      setError(errorText(failure));
      setBusy(false);
    }
  }

  if (hidden) return null;
  return (
    <section aria-labelledby="login-title" className="max-w-md">
      <h1 id="login-title" className="font-display text-4xl">{t("login.title")}</h1>
      <div className="mt-6 flex flex-col gap-5">
        {error && <Notice kind="error">{error}</Notice>}
        {step.kind === "password" ? (
          <form onSubmit={password} className="flex flex-col gap-5" noValidate>
            <Field label={t("login.name")} name="name" autoComplete="username webauthn" required maxLength={64} />
            <Field label={t("login.password")} name="password" type="password" autoComplete="current-password" required
              maxLength={256} />
            <Button type="submit" disabled={busy} className="self-start">{t("login.submit")}</Button>
          </form>
        ) : (
          <form onSubmit={totp} className="flex flex-col gap-5" noValidate>
            <p>{t("login.totp-lede")}</p>
            <Field ref={codeRef} label={t("login.code")} name="code" inputMode="numeric" autoComplete="one-time-code"
              pattern="[0-9 ]{6,7}" maxLength={7} required className="min-h-11 border border-line bg-sunken px-3 font-mono text-ink" />
            <div className="flex flex-wrap gap-3">
              <Button type="submit" disabled={busy}>{t("login.verify")}</Button>
              <Button type="button" variant="plain" onClick={() => setStep({ kind: "password" })}>{t("login.back")}</Button>
            </div>
          </form>
        )}
        {step.kind === "password" && (passkeys || info?.oidc) && (
          <div className="flex flex-col gap-3 border-t border-line pt-5">
            <p className="text-muted">{t("login.or")}</p>
            {passkeys && (
              <Button type="button" variant="plain" disabled={busy} onClick={passkey} className="self-start">
                {t("login.passkey")}
              </Button>
            )}
            {info?.oidc && (
              <a href="/api/panel/oidc/start" className="flex min-h-11 items-center self-start border-2 border-line bg-raised px-4 font-bold text-ink no-underline">
                {t("login.oidc", { name: info.oidc })}
              </a>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
