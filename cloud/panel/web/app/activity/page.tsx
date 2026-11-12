// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useEffect, useMemo, useState, type FormEvent } from "react";
import { Button, Notice, Panel, useErrorText } from "@/components/Form";
import { api } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";

interface Mode {
  mode: string;
  logins: number;
  "playtime-seconds": number;
  "peak-players": number;
}

interface AuditEntry {
  time: string;
  event: string;
  actor: string | null;
  target: string | null;
  address: string | null;
  details?: Record<string, unknown>;
}

interface LogHit {
  file: string;
  line: number;
  text: string;
}

function Statistics() {
  const { t, formatNumber } = useI18n();
  const errorText = useErrorText();
  const [modes, setModes] = useState<Mode[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<{ modes: Mode[] }>("/api/panel/statistics")
      .then((answer) => setModes(answer.modes))
      .catch((e) => {
        setModes([]);
        setError(errorText(e));
      });
  }, [errorText]);

  const hours = (seconds: number) => formatNumber(seconds / 3600, { maximumFractionDigits: 1 });
  return (
    <Panel id="activity-statistics" title={t("activity.statistics")}>
      <p className="text-muted">{t("activity.statistics-lede")}</p>
      {error && <Notice kind="error">{error}</Notice>}
      {modes === null ? (
        <p role="status" className="text-muted">{t("start.loading")}</p>
      ) : modes.length === 0 ? (
        <p>{t("activity.no-statistics")}</p>
      ) : (
        <div className="overflow-x-auto border border-line">
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">{t("activity.statistics")}</caption>
            <thead className="bg-raised">
              <tr>
                <th scope="col" className="px-3 py-2">{t("activity.mode")}</th>
                <th scope="col" className="px-3 py-2 text-right">{t("activity.logins")}</th>
                <th scope="col" className="px-3 py-2 text-right">{t("activity.playtime")}</th>
                <th scope="col" className="px-3 py-2 text-right">{t("activity.peak")}</th>
              </tr>
            </thead>
            <tbody>
              {modes.map((m) => (
                <tr key={m.mode} className="border-t border-line">
                  <th scope="row" className="px-3 py-2 font-mono font-normal">{m.mode}</th>
                  <td className="px-3 py-2 text-right tabular-nums">{formatNumber(m.logins)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{hours(m["playtime-seconds"])}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{formatNumber(m["peak-players"])}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Panel>
  );
}

function Audit() {
  const { t, formatDate } = useI18n();
  const errorText = useErrorText();
  const [entries, setEntries] = useState<AuditEntry[] | null>(null);
  const [filter, setFilter] = useState("");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api<{ entries: AuditEntry[] }>("/api/panel/audit?limit=500")
      .then((answer) => setEntries(answer.entries))
      .catch((e) => {
        setEntries([]);
        setError(errorText(e));
      });
  }, [errorText]);

  const shown = useMemo(() => {
    const needle = filter.trim().toLowerCase();
    if (!entries || !needle) return entries ?? [];
    return entries.filter((e) => [e.event, e.actor, e.target, e.address].some((v) => v?.toLowerCase().includes(needle)));
  }, [entries, filter]);

  return (
    <Panel id="activity-audit" title={t("activity.audit")}>
      <p className="text-muted">{t("activity.audit-lede")}</p>
      {error && <Notice kind="error">{error}</Notice>}
      <div className="flex flex-col gap-1">
        <label htmlFor="audit-filter" className="font-bold">{t("activity.filter")}</label>
        <input id="audit-filter" type="search" value={filter} onChange={(e) => setFilter(e.target.value)}
          autoComplete="off" className="min-h-11 max-w-md border border-line bg-sunken px-3 text-ink" />
      </div>
      {entries === null ? (
        <p role="status" className="text-muted">{t("start.loading")}</p>
      ) : (
        <>
          <p role="status" className="text-sm text-muted">{t("activity.shown", { shown: shown.length, total: entries.length })}</p>
          {shown.length > 0 && (
            <div className="max-h-[32rem] overflow-auto border border-line" tabIndex={0} role="region" aria-label={t("activity.audit")}>
              <table className="w-full border-collapse text-left text-sm">
                <thead className="sticky top-0 bg-raised">
                  <tr>
                    <th scope="col" className="px-3 py-2">{t("activity.time")}</th>
                    <th scope="col" className="px-3 py-2">{t("activity.event")}</th>
                    <th scope="col" className="px-3 py-2">{t("activity.actor")}</th>
                    <th scope="col" className="px-3 py-2">{t("activity.target")}</th>
                    <th scope="col" className="px-3 py-2">{t("activity.address")}</th>
                  </tr>
                </thead>
                <tbody>
                  {shown.map((e, i) => (
                    <tr key={i} className="border-t border-line">
                      <td className="whitespace-nowrap px-3 py-2">{formatDate(e.time)}</td>
                      <td className="px-3 py-2 font-mono">{e.event}</td>
                      <td className="px-3 py-2">{e.actor ?? "–"}</td>
                      <td className="px-3 py-2 font-mono">{e.target ?? "–"}</td>
                      <td className="px-3 py-2 font-mono">{e.address ?? "–"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}
    </Panel>
  );
}

function LogSearch() {
  const { t } = useI18n();
  const errorText = useErrorText();
  const [hits, setHits] = useState<LogHit[] | null>(null);
  const [truncated, setTruncated] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const search = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const text = String(new FormData(event.currentTarget).get("q") ?? "").trim();
    if (text.length < 2 || text.length > 200) {
      setError(t("activity.search-invalid"));
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const answer = await api<{ hits: LogHit[]; truncated: boolean }>(`/api/panel/logs?q=${encodeURIComponent(text)}&limit=500`);
      setHits(answer.hits);
      setTruncated(answer.truncated);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel id="activity-logs" title={t("activity.logs")}>
      <p className="text-muted">{t("activity.logs-lede")}</p>
      <form onSubmit={search} className="flex flex-col gap-2" noValidate role="search">
        <label htmlFor="log-query" className="font-bold">{t("activity.search")}</label>
        <div className="flex flex-wrap gap-3">
          <input id="log-query" name="q" type="search" minLength={2} maxLength={200} autoComplete="off"
            aria-invalid={error ? true : undefined} aria-describedby={error ? "log-error" : undefined}
            className="min-h-11 min-w-0 flex-1 border border-line bg-sunken px-3 font-mono text-ink aria-[invalid=true]:border-bad" />
          <Button type="submit" disabled={busy}>{t("activity.search-send")}</Button>
        </div>
        {error && <p id="log-error" role="alert" className="text-sm font-bold text-bad">{error}</p>}
      </form>
      {hits && (
        <>
          <p role="status" className="text-sm text-muted">
            {t(truncated ? "activity.hits-truncated" : "activity.hits", { count: hits.length })}
          </p>
          {hits.length > 0 && (
            <ol className="max-h-[32rem] overflow-auto border border-line bg-sunken p-3 font-mono text-sm" tabIndex={0}
              aria-label={t("activity.logs")}>
              {hits.map((h, i) => (
                <li key={i} className="whitespace-pre-wrap break-words">
                  <span className="text-muted">{h.file}:{h.line}</span> {h.text}
                </li>
              ))}
            </ol>
          )}
        </>
      )}
    </Panel>
  );
}

export default function ActivityPage() {
  const { t } = useI18n();
  const { can } = useAuth();
  const hidden = useGuard("private");
  if (hidden) return <p role="status" className="text-muted">{t("start.loading")}</p>;
  const any = can("node:read") || can("audit:read") || can("admin");
  if (!any) return <Notice kind="error">{t("error.missing-right")}</Notice>;
  return (
    <section aria-labelledby="activity-title" className="flex flex-col gap-6">
      <h1 id="activity-title" className="font-display text-4xl">{t("activity.title")}</h1>
      {can("node:read") && <Statistics />}
      {can("audit:read") && <Audit />}
      {can("admin") && <LogSearch />}
    </section>
  );
}
