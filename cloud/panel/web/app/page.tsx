// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useAuth, useGuard } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";

export default function StartPage() {
  const { t } = useI18n();
  const { info, session, down } = useAuth();
  const hidden = useGuard("private");

  if (down) {
    return (
      <section aria-labelledby="start-title" className="max-w-2xl">
        <h1 id="start-title" className="font-display text-4xl">{t("start.title")}</h1>
        <p role="alert" className="mt-4 font-bold text-bad">{t("start.unreachable-long")}</p>
      </section>
    );
  }
  if (hidden || !session || !info) {
    return <p role="status" className="text-muted">{t("start.loading")}</p>;
  }
  return (
    <section aria-labelledby="start-title" className="max-w-2xl">
      <h1 id="start-title" className="font-display text-4xl">{t("start.title")}</h1>
      <p className="mt-4 text-lg">{t("start.lede")}</p>
      <dl className="mt-8 grid grid-cols-[max-content_1fr] gap-x-6 gap-y-2 border border-line bg-raised p-5">
        <dt className="text-muted">{t("start.server")}</dt>
        <dd className="font-bold text-ok">{t("start.reachable")}</dd>
        <dt className="text-muted">{t("start.version")}</dt>
        <dd className="font-mono">{info.version}</dd>
        <dt className="text-muted">{t("start.user")}</dt>
        <dd>{session.user}</dd>
      </dl>
    </section>
  );
}
