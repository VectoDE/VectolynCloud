// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useCallback, useEffect, useState, type FormEvent } from "react";
import { Button, Notice, useErrorText } from "@/components/Form";
import { api } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useEvents, type NodeEvent } from "@/lib/events";
import { useI18n, type MessageKey } from "@/lib/i18n";

interface Player {
  uuid: string;
  name: string;
  proxy: string | null;
  service: string | null;
  bedrock: boolean;
}

type Action = "kick" | "ban" | "message";
type Message = { kind: "ok" | "error"; text: string } | null;

/** The same rule as the server: no control characters, at most 256 characters. */
const BAD_TEXT = new RegExp("[\u0000-\u001f\u007f-\u009f\u2028\u2029]");

export default function PlayersPage() {
  const { t } = useI18n();
  const { can } = useAuth();
  const errorText = useErrorText();
  const hidden = useGuard("private");
  const [players, setPlayers] = useState<Player[] | null>(null);
  const [filter, setFilter] = useState("");
  const [chosen, setChosen] = useState<{ player: Player; action: Action } | null>(null);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const manage = can("players:manage");
  const allowed = can("services:read");

  const load = useCallback(async () => {
    try {
      setPlayers((await api<{ players: Player[] }>("/api/panel/players")).players);
    } catch (error) {
      setPlayers([]);
      setMessage({ kind: "error", text: errorText(error) });
    }
  }, [errorText]);

  useEffect(() => {
    if (!hidden && allowed) void load();
  }, [hidden, allowed, load]);

  // A join, switch or leave changes the list: read it again, at most once a second.
  const [stale, setStale] = useState(false);
  const onEvent = useCallback((event: NodeEvent) => {
    if (event.type === "player") setStale(true);
  }, []);
  const stream = useEvents(["player"], [], onEvent, !hidden && allowed);
  useEffect(() => {
    if (!stale) return;
    const timer = setTimeout(() => {
      setStale(false);
      void load();
    }, 1000);
    return () => clearTimeout(timer);
  }, [stale, load]);

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!chosen) return;
    const text = String(new FormData(event.currentTarget).get("text") ?? "").trim();
    if (text.length > 256 || BAD_TEXT.test(text) || (chosen.action === "message" && !text)) {
      setMessage({ kind: "error", text: t("error.invalid-text") });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      await api(`/api/panel/players/${chosen.action}`, {
        body: chosen.action === "message" ? { uuid: chosen.player.uuid, text } : { uuid: chosen.player.uuid, reason: text },
      });
      setMessage({ kind: "ok", text: t(`players.done-${chosen.action}` as MessageKey, { name: chosen.player.name }) });
      setChosen(null);
      await load();
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    } finally {
      setBusy(false);
    }
  };

  if (hidden) return <p role="status" className="text-muted">{t("start.loading")}</p>;
  if (!allowed) return <Notice kind="error">{t("error.missing-right")}</Notice>;

  const shown = (players ?? []).filter((p) => p.name.toLowerCase().includes(filter.toLowerCase()));
  return (
    <section aria-labelledby="players-title" className="flex flex-col gap-5">
      <div className="flex flex-wrap items-baseline justify-between gap-4">
        <h1 id="players-title" className="font-display text-4xl">{t("players.title")}</h1>
        <p role="status" className="text-sm text-muted">{t(`stream.${stream}` as MessageKey)}</p>
      </div>
      {message && <Notice kind={message.kind}>{message.text}</Notice>}
      <div className="flex flex-col gap-1">
        <label htmlFor="players-filter" className="font-bold">{t("players.filter")}</label>
        <input id="players-filter" type="search" value={filter} onChange={(e) => setFilter(e.target.value)}
          autoComplete="off" className="min-h-11 max-w-md border border-line bg-sunken px-3 text-ink" />
      </div>

      {chosen && (
        <form onSubmit={submit} className="flex flex-col gap-3 border-2 border-primary bg-raised p-4" noValidate
          aria-labelledby="players-action-title">
          <h2 id="players-action-title" className="font-display text-2xl">
            {t(`players.${chosen.action}-title` as MessageKey, { name: chosen.player.name })}
          </h2>
          <label htmlFor="players-text" className="font-bold">
            {t(chosen.action === "message" ? "players.text" : "players.reason")}
          </label>
          <p id="players-text-hint" className="text-sm text-muted">
            {t(chosen.action === "message" ? "players.text-hint" : "players.reason-hint", { service: chosen.player.service ?? "–" })}
          </p>
          <input id="players-text" name="text" maxLength={256} autoComplete="off" autoFocus
            required={chosen.action === "message"} aria-describedby="players-text-hint"
            className="min-h-11 border border-line bg-sunken px-3 text-ink" />
          <div className="flex flex-wrap gap-3">
            <Button type="submit" variant={chosen.action === "message" ? "primary" : "danger"} disabled={busy}>
              {t(`players.${chosen.action}` as MessageKey)}
            </Button>
            <Button type="button" variant="plain" onClick={() => setChosen(null)}>{t("files.close")}</Button>
          </div>
        </form>
      )}

      {players === null ? (
        <p role="status" className="text-muted">{t("start.loading")}</p>
      ) : shown.length === 0 ? (
        <p>{t(players.length === 0 ? "players.none" : "players.no-match")}</p>
      ) : (
        <div className="overflow-x-auto border border-line">
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">{t("players.caption", { count: players.length })}</caption>
            <thead className="bg-raised">
              <tr>
                <th scope="col" className="px-3 py-2">{t("players.name")}</th>
                <th scope="col" className="px-3 py-2">{t("players.server")}</th>
                <th scope="col" className="px-3 py-2">{t("players.proxy")}</th>
                {manage && <th scope="col" className="px-3 py-2">{t("services.actions")}</th>}
              </tr>
            </thead>
            <tbody>
              {shown.map((p) => (
                <tr key={p.uuid} className="border-t border-line">
                  <th scope="row" className="px-3 py-2 font-normal">
                    <span className="font-bold">{p.name}</span>
                    {p.bedrock && <span className="ml-2 text-sm text-muted">{t("players.bedrock")}</span>}
                  </th>
                  <td className="px-3 py-2 font-mono">{p.service ?? t("players.switching")}</td>
                  <td className="px-3 py-2 font-mono">{p.proxy ?? "–"}</td>
                  {manage && (
                    <td className="px-3 py-2">
                      <div className="flex flex-wrap gap-2">
                        {(["message", "kick", "ban"] as Action[]).map((action) => (
                          <Button key={action} variant={action === "message" ? "plain" : "danger"}
                            disabled={busy || !p.service} onClick={() => setChosen({ player: p, action })}>
                            {t(`players.${action}` as MessageKey)}<span className="sr-only"> {p.name}</span>
                          </Button>
                        ))}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
