// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Button, Notice, useErrorText } from "@/components/Form";
import { api, type Service } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useEvents, type NodeEvent } from "@/lib/events";
import { useI18n, type MessageKey } from "@/lib/i18n";

type Message = { kind: "ok" | "error"; text: string } | null;

function StateBadge({ state }: { state: string }) {
  const { t, has } = useI18n();
  const key = `state.${state}`;
  const colour = state === "running" ? "border-ok text-ok" : state === "crashed" || state === "failed" ? "border-bad text-bad" : "border-line text-ink";
  return (
    <span className={`inline-block border-l-4 px-2 font-bold ${colour}`}>
      {has(key) ? t(key as MessageKey) : state}
    </span>
  );
}

export default function ServicesPage() {
  const { t, formatNumber } = useI18n();
  const { can } = useAuth();
  const errorText = useErrorText();
  const hidden = useGuard("private");
  const [services, setServices] = useState<Service[] | null>(null);
  const [tasks, setTasks] = useState<string[] | null>(null);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const write = can("services:write");

  const load = useCallback(async () => {
    try {
      setServices(await api<Service[]>("/api/v1/services"));
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    }
  }, [errorText]);

  useEffect(() => {
    if (hidden || !can("services:read")) return;
    void load();
    if (write && can("config:read")) {
      api<Record<string, unknown>>("/api/v1/tasks")
        .then((all) => setTasks(Object.keys(all).sort()))
        .catch(() => setTasks([]));
    }
  }, [hidden, can, write, load]);

  const onEvent = useCallback((event: NodeEvent) => {
    if (event.type !== "service-state") return;
    const changed = event.data.service as Service | undefined;
    if (!changed?.id) return;
    setServices((list) => {
      if (!list) return list;
      const index = list.findIndex((s) => s.id === changed.id);
      if (index < 0) return [...list, changed].sort((a, b) => a.id.localeCompare(b.id));
      const next = list.slice();
      next[index] = changed;
      return next;
    });
  }, []);
  const stream = useEvents(["service-state"], [], onEvent, !hidden && can("services:read"));

  const act = async (id: string, action: "stop" | "restart") => {
    if (action === "stop" && !window.confirm(t("services.confirm-stop", { id }))) return;
    setBusy(id);
    setMessage(null);
    try {
      await api(`/api/v1/services/${encodeURIComponent(id)}/${action}`, { body: {} });
      setMessage({ kind: "ok", text: t(action === "stop" ? "services.stopped" : "services.restarted", { id }) });
      await load();
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    } finally {
      setBusy(null);
    }
  };

  const start = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const task = String(new FormData(event.currentTarget).get("task") ?? "").trim();
    if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(task)) {
      setMessage({ kind: "error", text: t("services.task-invalid") });
      return;
    }
    setBusy("start");
    setMessage(null);
    try {
      const created = await api<Service>("/api/v1/services", { body: { task } });
      setMessage({ kind: "ok", text: t("services.started", { id: created.id }) });
      await load();
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    } finally {
      setBusy(null);
    }
  };

  if (hidden) return <p role="status" className="text-muted">{t("start.loading")}</p>;
  if (!can("services:read")) {
    return <Notice kind="error">{t("error.missing-right")}</Notice>;
  }
  return (
    <section aria-labelledby="services-title" className="flex flex-col gap-6">
      <div className="flex flex-wrap items-baseline justify-between gap-4">
        <h1 id="services-title" className="font-display text-4xl">{t("services.title")}</h1>
        <p role="status" className="text-sm text-muted">{t(`stream.${stream}` as MessageKey)}</p>
      </div>
      {message && <Notice kind={message.kind}>{message.text}</Notice>}

      {write && (
        <form onSubmit={start} className="flex flex-wrap items-end gap-3 border border-line bg-raised p-4" noValidate>
          <div className="flex flex-col gap-1">
            <label htmlFor="start-task" className="font-bold">{t("services.task")}</label>
            {tasks && tasks.length > 0 ? (
              <select id="start-task" name="task" className="min-h-11 border border-line bg-sunken px-2 text-ink">
                {tasks.map((task) => <option key={task} value={task}>{task}</option>)}
              </select>
            ) : (
              <input id="start-task" name="task" required maxLength={64} autoComplete="off"
                className="min-h-11 border border-line bg-sunken px-3 text-ink" />
            )}
          </div>
          <Button type="submit" disabled={busy !== null}>{t("services.start")}</Button>
        </form>
      )}

      {services === null ? (
        <p role="status" className="text-muted">{t("start.loading")}</p>
      ) : services.length === 0 ? (
        <p>{t("services.none")}</p>
      ) : (
        <div className="overflow-x-auto border border-line">
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">{t("services.caption")}</caption>
            <thead className="bg-raised">
              <tr>
                <th scope="col" className="px-3 py-2">{t("services.id")}</th>
                <th scope="col" className="px-3 py-2">{t("services.task")}</th>
                <th scope="col" className="px-3 py-2">{t("services.state")}</th>
                <th scope="col" className="px-3 py-2">{t("services.port")}</th>
                <th scope="col" className="px-3 py-2">{t("services.players")}</th>
                <th scope="col" className="px-3 py-2">{t("services.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {services.map((s) => (
                <tr key={s.id} className="border-t border-line">
                  <th scope="row" className="px-3 py-2 font-mono">{s.id}</th>
                  <td className="px-3 py-2">{s.task}</td>
                  <td className="px-3 py-2"><StateBadge state={s.state} /></td>
                  <td className="px-3 py-2 font-mono">{s.port}</td>
                  <td className="px-3 py-2">
                    {s["players-online"] === undefined ? "–" : `${formatNumber(s["players-online"])} / ${formatNumber(s["max-players"] ?? 0)}`}
                  </td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-2">
                      <a href={`/services/console/?id=${encodeURIComponent(s.id)}`}
                        className="flex min-h-11 items-center px-2 text-primary underline underline-offset-4">
                        {t("services.console")}<span className="sr-only"> {s.id}</span>
                      </a>
                      {can("files:read") && (
                        <a href={`/services/files/?id=${encodeURIComponent(s.id)}`}
                          className="flex min-h-11 items-center px-2 text-primary underline underline-offset-4">
                          {t("services.files")}<span className="sr-only"> {s.id}</span>
                        </a>
                      )}
                      {write && (
                        <>
                          <Button variant="plain" disabled={busy !== null} onClick={() => void act(s.id, "restart")}>
                            {t("services.restart")}<span className="sr-only"> {s.id}</span>
                          </Button>
                          <Button variant="danger" disabled={busy !== null} onClick={() => void act(s.id, "stop")}>
                            {t("services.stop")}<span className="sr-only"> {s.id}</span>
                          </Button>
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

