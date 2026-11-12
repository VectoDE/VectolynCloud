// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Button, Field, Notice, Panel, useErrorText } from "@/components/Form";
import { api } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";

interface User {
  name: string;
  roles: string[];
  disabled: boolean;
  totp: boolean;
  passkeys: number;
  oidc: boolean;
  "created-at": string;
}

interface Role {
  name: string;
  rights: string[];
  "built-in": boolean;
}

type Message = { kind: "ok" | "error"; text: string } | null;

function RoleChoice({ roles, chosen, name, legend }: { roles: Role[]; chosen: string[]; name: string; legend: string }) {
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="font-bold">{legend}</legend>
      <div className="flex flex-wrap gap-x-5">
        {roles.map((role) => (
          <label key={role.name} className="flex min-h-11 items-center gap-2">
            <input type="checkbox" name={name} value={role.name} defaultChecked={chosen.includes(role.name)}
              className="size-5 accent-[var(--vl-color-primary)]" />
            {role.name}
          </label>
        ))}
      </div>
    </fieldset>
  );
}

export default function UsersPage() {
  const { t, formatDate } = useI18n();
  const { session, can } = useAuth();
  const errorText = useErrorText();
  const hidden = useGuard("private");
  const [users, setUsers] = useState<User[]>([]);
  const [roles, setRoles] = useState<Role[]>([]);
  const [rights, setRights] = useState<string[]>([]);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const allowed = can("users:manage");

  const load = useCallback(async () => {
    const [u, r] = await Promise.all([
      api<{ users: User[] }>("/api/panel/users"),
      api<{ roles: Role[]; rights: string[] }>("/api/panel/roles"),
    ]);
    setUsers(u.users);
    setRoles(r.roles);
    setRights(r.rights);
  }, []);

  useEffect(() => {
    if (session && allowed) load().catch((failure) => setMessage({ kind: "error", text: errorText(failure) }));
  }, [session, allowed, load, errorText]);

  async function run(work: () => Promise<unknown>, ok: string) {
    setBusy(true);
    setMessage(null);
    try {
      await work();
      await load();
      setMessage({ kind: "ok", text: ok });
      return true;
    } catch (failure) {
      setMessage({ kind: "error", text: errorText(failure) });
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function createUser(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const name = String(form.get("name") ?? "").trim();
    if (await run(() => api("/api/panel/users", {
      body: { name, password: String(form.get("password") ?? ""), roles: form.getAll("roles").map(String) },
    }), t("users.created", { name }))) {
      formElement.reset();
    }
  }

  async function saveRole(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const formElement = event.currentTarget;
    const form = new FormData(formElement);
    const name = String(form.get("name") ?? "").trim();
    if (await run(() => api("/api/panel/roles", { body: { name, rights: form.getAll("rights").map(String) } }),
      t("users.role-saved", { name }))) {
      formElement.reset();
    }
  }

  if (hidden || !session) return null;
  if (!allowed) {
    return <Notice kind="error">{t("error.missing-right")}</Notice>;
  }
  return (
    <div className="flex flex-col gap-8">
      <h1 className="font-display text-4xl">{t("users.title")}</h1>
      {message && <Notice kind={message.kind}>{message.text}</Notice>}

      <Panel id="user-list" title={t("users.list")}>
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">{t("users.list")}</caption>
            <thead>
              <tr className="border-b border-line">
                <th scope="col" className="py-2 pr-4">{t("users.name")}</th>
                <th scope="col" className="py-2 pr-4">{t("users.roles")}</th>
                <th scope="col" className="py-2 pr-4">{t("users.factors")}</th>
                <th scope="col" className="py-2 pr-4">{t("users.since")}</th>
                <th scope="col" className="py-2">{t("users.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {users.map((user) => (
                <tr key={user.name} className="border-b border-line align-top">
                  <th scope="row" className="py-3 pr-4 font-bold">
                    {user.name}
                    {user.disabled && <span className="ml-2 text-bad">({t("users.disabled")})</span>}
                  </th>
                  <td className="py-3 pr-4">
                    <form className="flex flex-col gap-2" onSubmit={(event) => {
                      event.preventDefault();
                      const chosen = new FormData(event.currentTarget).getAll("roles").map(String);
                      void run(() => api("/api/panel/users/update", { body: { name: user.name, roles: chosen } }),
                        t("users.updated", { name: user.name }));
                    }}>
                      <RoleChoice roles={roles} chosen={user.roles} name="roles" legend={t("users.roles-of", { name: user.name })} />
                      <Button type="submit" variant="plain" disabled={busy} className="self-start">{t("users.save-roles")}</Button>
                    </form>
                  </td>
                  <td className="py-3 pr-4">
                    {[user.totp && "TOTP", user.passkeys > 0 && t("users.passkeys", { count: user.passkeys }), user.oidc && "SSO"]
                      .filter(Boolean).join(", ") || t("users.password-only")}
                  </td>
                  <td className="py-3 pr-4">{formatDate(user["created-at"])}</td>
                  <td className="py-3">
                    <div className="flex flex-wrap gap-2">
                      <Button type="button" variant="plain" disabled={busy}
                        onClick={() => void run(() => api("/api/panel/users/update", {
                          body: { name: user.name, disabled: !user.disabled },
                        }), t("users.updated", { name: user.name }))}>
                        {user.disabled ? t("users.enable") : t("users.disable")}
                      </Button>
                      <Button type="button" variant="plain" disabled={busy}
                        onClick={() => {
                          if (window.confirm(t("users.reset-confirm", { name: user.name }))) {
                            void run(() => api("/api/panel/users/update", { body: { name: user.name, "reset-factors": true } }),
                              t("users.updated", { name: user.name }));
                          }
                        }}>
                        {t("users.reset-factors")}
                      </Button>
                      <Button type="button" variant="danger" disabled={busy || user.name === session.user}
                        onClick={() => {
                          if (window.confirm(t("users.delete-confirm", { name: user.name }))) {
                            void run(() => api("/api/panel/users/delete", { body: { name: user.name } }),
                              t("users.deleted", { name: user.name }));
                          }
                        }}>
                        {t("users.delete")}
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </Panel>

      <Panel id="user-new" title={t("users.new")}>
        <form onSubmit={createUser} className="flex max-w-xl flex-col gap-4" noValidate>
          <Field label={t("users.name")} name="name" required maxLength={32} autoComplete="off"
            pattern="[A-Za-z0-9][A-Za-z0-9_.\-]{0,31}" />
          <Field label={t("users.initial-password")} hint={t("password.rules")} name="password" type="password"
            autoComplete="new-password" required minLength={12} maxLength={256} />
          <RoleChoice roles={roles} chosen={["viewer"]} name="roles" legend={t("users.roles")} />
          <Button type="submit" disabled={busy} className="self-start">{t("users.create")}</Button>
        </form>
      </Panel>

      <Panel id="role-list" title={t("users.role-list")}>
        <ul className="flex flex-col gap-3">
          {roles.map((role) => (
            <li key={role.name} className="border border-line bg-surface px-3 py-2">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <span className="font-bold">
                  {role.name} {role["built-in"] && <span className="font-normal text-muted">({t("users.built-in")})</span>}
                </span>
                {!role["built-in"] && (
                  <Button type="button" variant="danger" disabled={busy}
                    aria-label={t("users.role-delete-named", { name: role.name })}
                    onClick={() => void run(() => api("/api/panel/roles/delete", { body: { name: role.name } }),
                      t("users.role-deleted", { name: role.name }))}>
                    {t("users.delete")}
                  </Button>
                )}
              </div>
              <p className="mt-1 font-mono text-sm text-muted">{role.rights.join(", ")}</p>
            </li>
          ))}
        </ul>
        <form onSubmit={saveRole} className="flex flex-col gap-4 border-t border-line pt-4" noValidate>
          <h3 className="font-bold">{t("users.role-new")}</h3>
          <Field label={t("users.role-name")} hint={t("users.role-name-hint")} name="name" required maxLength={32}
            pattern="[a-z][a-z0-9\-]{0,31}" autoComplete="off" />
          <fieldset className="flex flex-col gap-1">
            <legend className="font-bold">{t("users.rights")}</legend>
            <div className="grid gap-x-5 sm:grid-cols-2 lg:grid-cols-3">
              {rights.map((right) => (
                <label key={right} className="flex min-h-11 items-center gap-2 font-mono text-sm">
                  <input type="checkbox" name="rights" value={right} className="size-5 accent-[var(--vl-color-primary)]" />
                  {right}
                </label>
              ))}
            </div>
          </fieldset>
          <Button type="submit" disabled={busy} className="self-start">{t("users.role-save")}</Button>
        </form>
      </Panel>
    </div>
  );
}
