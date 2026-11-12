// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useCallback, useId, type ButtonHTMLAttributes, type ComponentPropsWithRef, type ReactNode } from "react";
import { ApiError } from "@/lib/api";
import { useI18n, type MessageKey } from "@/lib/i18n";

/** A labelled input with an optional hint and error, both tied to it for screen readers. */
export function Field({
  label,
  hint,
  error,
  ...input
}: { label: string; hint?: string; error?: string | null } & ComponentPropsWithRef<"input">) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="font-bold">
        {label}
      </label>
      {hint && (
        <p id={hintId} className="text-sm text-muted">
          {hint}
        </p>
      )}
      <input
        id={id}
        aria-describedby={[hintId, errorId].filter(Boolean).join(" ") || undefined}
        aria-invalid={error ? true : undefined}
        className="min-h-11 border border-line bg-sunken px-3 text-ink aria-[invalid=true]:border-bad"
        {...input}
      />
      {error && (
        <p id={errorId} className="text-sm font-bold text-bad">
          {error}
        </p>
      )}
    </div>
  );
}

export function Button({
  variant = "primary",
  className = "",
  ...props
}: { variant?: "primary" | "plain" | "danger" } & ButtonHTMLAttributes<HTMLButtonElement>) {
  const look =
    variant === "primary"
      ? "bg-primary text-surface border-primary"
      : variant === "danger"
        ? "bg-surface text-bad border-bad"
        : "bg-raised text-ink border-line";
  return (
    <button
      className={`min-h-11 min-w-11 border-2 px-4 font-bold disabled:cursor-not-allowed disabled:opacity-60 ${look} ${className}`}
      {...props}
    />
  );
}

/** A message that screen readers announce: errors as alerts, the rest as status. */
export function Notice({ kind, children }: { kind: "error" | "ok" | "info"; children: ReactNode }) {
  const colour = kind === "error" ? "border-bad text-bad" : kind === "ok" ? "border-ok text-ok" : "border-line text-ink";
  return (
    <div role={kind === "error" ? "alert" : "status"} className={`border-l-4 bg-raised px-4 py-3 font-bold ${colour}`}>
      {children}
    </div>
  );
}

/** The text of an error for the user: the translation of its code, else the server's message. */
export function useErrorText() {
  const { t, has } = useI18n();
  return useCallback((error: unknown): string => {
    if (error instanceof ApiError) {
      const key = `error.${error.code}` as MessageKey;
      if (has(key)) return t(key);
      return error.message || t("error.unknown");
    }
    if (error instanceof DOMException && (error.name === "NotAllowedError" || error.name === "AbortError")) {
      return t("error.passkey-cancelled");
    }
    return t("error.network");
  }, [t, has]);
}

/** A titled block of a page. */
export function Panel({ title, children, id }: { title: string; children: ReactNode; id: string }) {
  return (
    <section aria-labelledby={id} className="border border-line bg-raised p-5">
      <h2 id={id} className="font-display text-2xl">
        {title}
      </h2>
      <div className="mt-4 flex flex-col gap-4">{children}</div>
    </section>
  );
}
