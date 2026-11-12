// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useEffect, useState, type FormEvent } from "react";
import { Button, Field, Notice, Panel, useErrorText } from "@/components/Form";
import { api, type Session } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useI18n, type MessageKey } from "@/lib/i18n";
import { createPasskey, passkeysSupported } from "@/lib/webauthn";

type Message = { kind: "ok" | "error"; text: string } | null;

export default function AccountPage() {
  const { t, formatDate } = useI18n();
  const { session, refresh } = useAuth();
  const errorText = useErrorText();
  const hidden = useGuard("private");
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const [enrol, setEnrol] = useState<{ ticket: string; secret: string; uri: string } | null>(null);
  const [mismatch, setMismatch] = useState(false);
  const [canPasskey, setCanPasskey] = useState(false);

  useEffect(() => {
    setCanPasskey(passkeysSupported());
    const params = new URLSearchParams(window.location.search);
    const code = params.get("error");
    if (code && /^[a-z-]{1,40}$/.test(code)) setMessage({ kind: "error", text: t(`error.${code}` as MessageKey) });
    if (params.get("linked") === "oidc") setMessage({ kind: "ok", text: t("account.oidc-linked") });
  }, [t]);

  async function run(work: () => Promise<unknown>, ok: string) {
    setBusy(true);
    setMessage(null);
    try {
      await work();
      await refresh();
      setMessage({ kind: "ok", text: ok });
      return true;
    } catch (failure) {
      setMessage({ kind: "error", text: errorText(failure) });
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function changePassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const password = String(form.get("password") ?? "");
    if (password !== String(form.get("confirm") ?? "")) {
      setMismatch(true);
      return;
    }
    setMismatch(false);
    if (await run(() => api("/api/panel/account/password", {
      body: { current: String(form.get("current") ?? ""), password },
    }), t("account.password-changed"))) {
      formElement.reset();
    }
  }

  async function startTotp() {
    setBusy(true);
    setMessage(null);
    try {
      setEnrol(await api<{ ticket: string; secret: string; uri: string }>("/api/panel/account/totp/start", { body: {} }));
    } catch (failure) {
      setMessage({ kind: "error", text: errorText(failure) });
    } finally {
      setBusy(false);
    }
  }

  async function confirmTotp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!enrol) return;
    const form = new FormData(event.currentTarget);
    // The ticket is used up either way; a wrong code starts the enrolment over.
    await run(() => api("/api/panel/account/totp/confirm", {
      body: { ticket: enrol.ticket, code: String(form.get("code") ?? "") },
    }), t("account.totp-on"));
    setEnrol(null);
  }

  async function disableTotp(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    await run(() => api("/api/panel/account/totp/delete", { body: { password: String(form.get("password") ?? "") } }),
      t("account.totp-off"));
  }

  async function addPasskey(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const label = String(new FormData(formElement).get("label") ?? "").trim();
    if (await run(async () => {
      const options = await api<Parameters<typeof createPasskey>[0]>("/api/panel/account/passkeys/options", { body: {} });
      await api("/api/panel/account/passkeys", { body: await createPasskey(options, label) });
    }, t("account.passkey-added"))) {
      formElement.reset();
    }
  }

  async function linkOidc() {
    setBusy(true);
    try {
      const reply = await api<{ url: string }>("/api/panel/account/oidc/start", { body: {} });
      window.location.assign(reply.url);
    } catch (failure) {
      setMessage({ kind: "error", text: errorText(failure) });
      setBusy(false);
    }
  }

  if (hidden || !session) return null;
  const me: Session = session;
  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <div>
        <h1 className="font-display text-4xl">{t("account.title")}</h1>
        <p className="mt-2">{t("account.signed-in-as", { name: me.user, roles: me.roles.join(", ") })}</p>
      </div>
      {message && <Notice kind={message.kind}>{message.text}</Notice>}

      <Panel id="password-title" title={t("account.password")}>
        <form onSubmit={changePassword} className="flex flex-col gap-4" noValidate>
          <Field label={t("account.current-password")} name="current" type="password" autoComplete="current-password" required />
          <Field label={t("account.new-password")} hint={t("password.rules")} name="password" type="password"
            autoComplete="new-password" minLength={12} maxLength={256} required />
          <Field label={t("setup.confirm")} name="confirm" type="password" autoComplete="new-password" required
            error={mismatch ? t("password.mismatch") : null} />
          <Button type="submit" disabled={busy} className="self-start">{t("account.change-password")}</Button>
        </form>
      </Panel>

      <Panel id="totp-title" title={t("account.totp")}>
        <p>{me.totp ? t("account.totp-state-on") : t("account.totp-state-off")}</p>
        {!me.totp && !enrol && (
          <Button type="button" disabled={busy} onClick={startTotp} className="self-start">{t("account.totp-start")}</Button>
        )}
        {enrol && (
          <form onSubmit={confirmTotp} className="flex flex-col gap-4" noValidate>
            <p>{t("account.totp-scan")}</p>
            <p>
              <a href={enrol.uri} className="text-primary underline underline-offset-4">{t("account.totp-open-app")}</a>
            </p>
            <p>
              {t("account.totp-secret")}{" "}
              <code className="break-all bg-sunken px-1 font-mono">{enrol.secret.replace(/(.{4})/g, "$1 ").trim()}</code>
            </p>
            <Field label={t("login.code")} name="code" inputMode="numeric" autoComplete="one-time-code" maxLength={7} required />
            <Button type="submit" disabled={busy} className="self-start">{t("account.totp-confirm")}</Button>
          </form>
        )}
        {me.totp && (
          <form onSubmit={disableTotp} className="flex flex-col gap-4" noValidate>
            <Field label={t("login.password")} name="password" type="password" autoComplete="current-password" required />
            <Button type="submit" variant="danger" disabled={busy} className="self-start">{t("account.totp-disable")}</Button>
          </form>
        )}
      </Panel>

      <Panel id="passkeys-title" title={t("account.passkeys")}>
        {me.passkeys.length === 0 ? (
          <p>{t("account.passkeys-none")}</p>
        ) : (
          <ul className="flex flex-col gap-2">
            {me.passkeys.map((key) => (
              <li key={key.id} className="flex flex-wrap items-center justify-between gap-3 border border-line bg-surface px-3 py-2">
                <span>
                  <span className="font-bold">{key.label}</span>{" "}
                  <span className="text-muted">{t("account.added", { date: formatDate(key["created-at"]) })}</span>
                </span>
                <Button type="button" variant="danger" disabled={busy}
                  aria-label={t("account.passkey-remove-named", { name: key.label })}
                  onClick={() => void run(() => api("/api/panel/account/passkeys/delete", { body: { id: key.id } }),
                    t("account.passkey-removed"))}>
                  {t("account.remove")}
                </Button>
              </li>
            ))}
          </ul>
        )}
        {canPasskey ? (
          <form onSubmit={addPasskey} className="flex flex-col gap-4" noValidate>
            <Field label={t("account.passkey-label")} hint={t("account.passkey-hint")} name="label" maxLength={64} />
            <Button type="submit" disabled={busy} className="self-start">{t("account.passkey-add")}</Button>
          </form>
        ) : (
          <p className="text-muted">{t("account.passkeys-unsupported")}</p>
        )}
      </Panel>

      {me.oidc && (
        <Panel id="oidc-title" title={t("account.oidc", { name: me.oidc })}>
          {me["oidc-links"].length === 0 ? (
            <>
              <p>{t("account.oidc-none")}</p>
              <Button type="button" disabled={busy} onClick={linkOidc} className="self-start">{t("account.oidc-link")}</Button>
            </>
          ) : (
            <ul className="flex flex-col gap-2">
              {me["oidc-links"].map((link) => (
                <li key={link.issuer + link.subject} className="flex flex-wrap items-center justify-between gap-3 border border-line bg-surface px-3 py-2">
                  <span className="break-all font-mono text-sm">{link.subject}</span>
                  <Button type="button" variant="danger" disabled={busy}
                    onClick={() => void run(() => api("/api/panel/account/oidc/delete", { body: link }), t("account.oidc-unlinked"))}>
                    {t("account.remove")}
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </Panel>
      )}
    </div>
  );
}
