// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Button, Notice, useErrorText } from "@/components/Form";
import { api } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useEvents, type NodeEvent } from "@/lib/events";
import { useI18n, type MessageKey } from "@/lib/i18n";

/** Lines kept on the page; older ones drop out. */
const MAX_LINES = 1000;
const SERVICE_ID = /^[a-z0-9][a-z0-9_.-]{0,63}$/;

interface Line {
  n: number;
  text: string;
  mine: boolean;
}

export default function ConsolePage() {
  const { t } = useI18n();
  const { can } = useAuth();
  const errorText = useErrorText();
  const hidden = useGuard("private");
  const [id, setId] = useState<string | null>(null);
  const [lines, setLines] = useState<Line[]>([]);
  const [follow, setFollow] = useState(true);
  const [announce, setAnnounce] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const counter = useRef(0);
  const log = useRef<HTMLDivElement>(null);
  const write = can("services:write");

  useEffect(() => {
    const value = new URLSearchParams(window.location.search).get("id") ?? "";
    setId(SERVICE_ID.test(value) ? value : "");
  }, []);

  const add = useCallback((text: string, mine: boolean) => {
    setLines((current) => {
      const next = [...current, { n: ++counter.current, text, mine }];
      return next.length > MAX_LINES ? next.slice(next.length - MAX_LINES) : next;
    });
  }, []);

  const onEvent = useCallback((event: NodeEvent) => {
    if (event.type === "service-output" && event.data.service === id && typeof event.data.line === "string") {
      add(event.data.line, false);
    } else if (event.type === "dropped") {
      add(t("console.dropped", { count: Number(event.data.count ?? 0) }), true);
    }
  }, [id, add, t]);
  const stream = useEvents(["service-output"], id ? [id] : [], onEvent, !hidden && !!id && can("services:read"));

  useEffect(() => {
    if (follow && log.current) log.current.scrollTop = log.current.scrollHeight;
  }, [lines, follow]);

  const send = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const command = String(new FormData(form).get("command") ?? "").trim();
    if (!command || command.length > 256) {
      setError(t("console.command-invalid"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await api(`/api/v1/services/${encodeURIComponent(id ?? "")}/command`, { body: { command } });
      add(`> ${command}`, true);
      form.reset();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  if (hidden || id === null) return <p role="status" className="text-muted">{t("start.loading")}</p>;
  if (!can("services:read")) return <Notice kind="error">{t("error.missing-right")}</Notice>;
  if (!id) return <Notice kind="error">{t("console.no-service")}</Notice>;

  return (
    <section aria-labelledby="console-title" className="flex flex-col gap-4">
      <p><a href="/services/" className="text-primary underline underline-offset-4">{t("services.back")}</a></p>
      <div className="flex flex-wrap items-baseline justify-between gap-4">
        <h1 id="console-title" className="font-display text-4xl">
          {t("console.title")} <span className="font-mono text-2xl">{id}</span>
        </h1>
        <p role="status" className="text-sm text-muted">{t(`stream.${stream}` as MessageKey)}</p>
      </div>
      <p className="text-muted">{t("console.lede")}</p>
      <div className="flex flex-wrap gap-x-6">
        <label className="flex min-h-11 items-center gap-2">
          <input type="checkbox" checked={follow} onChange={(e) => setFollow(e.target.checked)}
            className="size-5 accent-[var(--vl-color-primary)]" />
          {t("console.follow")}
        </label>
        <label className="flex min-h-11 items-center gap-2">
          <input type="checkbox" checked={announce} onChange={(e) => setAnnounce(e.target.checked)}
            className="size-5 accent-[var(--vl-color-primary)]" />
          {t("console.announce")}
        </label>
        <Button variant="plain" type="button" onClick={() => setLines([])}>{t("console.clear")}</Button>
      </div>
      <div ref={log} role="log" aria-live={announce ? "polite" : "off"} aria-label={t("console.output")} tabIndex={0}
        className="h-[28rem] overflow-y-auto border border-line bg-sunken p-3 font-mono text-sm leading-relaxed focus-visible:outline-2">
        {lines.length === 0 ? (
          <p className="text-muted">{t("console.empty")}</p>
        ) : (
          lines.map((line) => (
            <p key={line.n} className={`whitespace-pre-wrap break-words ${line.mine ? "text-primary" : ""}`}>{line.text}</p>
          ))
        )}
      </div>
      {write && (
        <form onSubmit={send} className="flex flex-col gap-2" noValidate>
          <label htmlFor="console-command" className="font-bold">{t("console.command")}</label>
          <div className="flex flex-wrap gap-3">
            <input id="console-command" name="command" maxLength={256} autoComplete="off" spellCheck={false}
              aria-invalid={error ? true : undefined} aria-describedby={error ? "console-error" : undefined}
              className="min-h-11 min-w-0 flex-1 border border-line bg-sunken px-3 font-mono text-ink aria-[invalid=true]:border-bad" />
            <Button type="submit" disabled={busy}>{t("console.send")}</Button>
          </div>
          {error && <p id="console-error" role="alert" className="text-sm font-bold text-bad">{error}</p>}
        </form>
      )}
    </section>
  );
}
