// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useEffect, useState } from "react";
import { useI18n } from "@/lib/i18n";

interface PanelInfo {
  name: string;
  version: string;
}

type State = { kind: "loading" } | { kind: "ok"; info: PanelInfo } | { kind: "down" };

export default function StartPage() {
  const { t } = useI18n();
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();
    fetch("/api/panel/info", { signal: controller.signal, credentials: "same-origin" })
      .then((response) => (response.ok ? response.json() : Promise.reject(new Error(String(response.status)))))
      .then((info: PanelInfo) => setState({ kind: "ok", info }))
      .catch((error: unknown) => {
        if (!(error instanceof DOMException && error.name === "AbortError")) setState({ kind: "down" });
      });
    return () => controller.abort();
  }, []);

  return (
    <section aria-labelledby="start-title" className="max-w-2xl">
      <h1 id="start-title" className="font-display text-4xl">{t("start.title")}</h1>
      <p className="mt-4 text-lg">{t("start.lede")}</p>
      <dl className="mt-8 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 border border-line bg-raised p-5">
        <dt className="text-muted">{t("start.server")}</dt>
        <dd>
          <span role="status">
            {state.kind === "loading" && t("start.loading")}
            {state.kind === "ok" && <span className="font-bold text-ok">{t("start.reachable")}</span>}
            {state.kind === "down" && <span className="font-bold text-bad">{t("start.unreachable")}</span>}
          </span>
        </dd>
        {state.kind === "ok" && (
          <>
            <dt className="text-muted">{t("start.version")}</dt>
            <dd className="font-mono">{state.info.version}</dd>
          </>
        )}
      </dl>
    </section>
  );
}
