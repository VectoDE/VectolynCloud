// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Button, Notice, useErrorText } from "@/components/Form";
import { api, download, upload } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useI18n } from "@/lib/i18n";

interface Entry {
  name: string;
  folder: boolean;
  size: number;
  modified: string;
}

type Message = { kind: "ok" | "error"; text: string } | null;

const SERVICE_ID = /^[a-z0-9][a-z0-9_.-]{0,63}$/;
/** Largest file the editor opens; larger ones are only downloaded. */
const EDIT_LIMIT = 1024 * 1024;
/** Largest upload the server takes. */
const UPLOAD_LIMIT = 8 * 1024 * 1024;
/** What the server refuses in a name, checked here first for a quick answer. */
const BAD_NAME = /[\\/:*?"<>|\u0000-\u001f]|^\.{1,2}$|[. ]$/;

function join(folder: string, name: string) {
  return folder ? `${folder}/${name}` : name;
}

function query(id: string, path: string) {
  return `service=${encodeURIComponent(id)}&path=${encodeURIComponent(path)}`;
}

export default function FilesPage() {
  const { t, formatNumber, formatDate } = useI18n();
  const { can } = useAuth();
  const errorText = useErrorText();
  const hidden = useGuard("private");
  const [id, setId] = useState<string | null>(null);
  const [folder, setFolder] = useState("");
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [open, setOpen] = useState<{ path: string; text: string } | null>(null);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const heading = useRef<HTMLHeadingElement>(null);
  const editor = useRef<HTMLTextAreaElement>(null);
  const write = can("files:write");

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const value = params.get("id") ?? "";
    setId(SERVICE_ID.test(value) ? value : "");
    setFolder(params.get("path") ?? "");
  }, []);

  const load = useCallback(async (service: string, path: string) => {
    setEntries(null);
    try {
      const answer = await api<{ entries: Entry[] }>(`/api/panel/files?${query(service, path)}`);
      setEntries(answer.entries);
    } catch (error) {
      setEntries([]);
      setMessage({ kind: "error", text: errorText(error) });
    }
  }, [errorText]);

  useEffect(() => {
    if (hidden || !id || !can("files:read")) return;
    void load(id, folder);
    const url = `/services/files/?id=${encodeURIComponent(id)}${folder ? `&path=${encodeURIComponent(folder)}` : ""}`;
    window.history.replaceState(null, "", url);
  }, [hidden, id, folder, can, load]);

  const goTo = (path: string) => {
    setOpen(null);
    setMessage(null);
    setFolder(path);
    heading.current?.focus();
  };

  const openFile = async (entry: Entry) => {
    if (!id) return;
    const path = join(folder, entry.name);
    if (entry.size > EDIT_LIMIT) {
      setMessage({ kind: "error", text: t("files.too-large-to-edit") });
      return;
    }
    setMessage(null);
    let bytes: ArrayBuffer;
    try {
      bytes = await download(`/api/panel/files/content?${query(id, path)}`);
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
      return;
    }
    let text: string;
    try {
      text = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      setMessage({ kind: "error", text: t("files.not-text") });
      return;
    }
    setOpen({ path, text });
    setTimeout(() => editor.current?.focus(), 0);
  };

  const run = async (work: () => Promise<void>, ok: string) => {
    setBusy(true);
    setMessage(null);
    try {
      await work();
      setMessage({ kind: "ok", text: ok });
      if (id) await load(id, folder);
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    } finally {
      setBusy(false);
    }
  };

  const save = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!id || !open) return;
    const text = editor.current?.value ?? "";
    void run(async () => {
      await upload(`/api/panel/files/write?${query(id, open.path)}`, text);
      setOpen({ path: open.path, text });
    }, t("files.saved", { name: open.path }));
  };

  const sendFiles = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const input = form.elements.namedItem("upload") as HTMLInputElement;
    const file = input.files?.[0];
    if (!id || !file) {
      setMessage({ kind: "error", text: t("files.choose-file") });
      return;
    }
    if (file.size > UPLOAD_LIMIT) {
      setMessage({ kind: "error", text: t("error.body-too-large") });
      return;
    }
    if (BAD_NAME.test(file.name)) {
      setMessage({ kind: "error", text: t("error.invalid-path") });
      return;
    }
    void run(async () => {
      await upload(`/api/panel/files/write?${query(id, join(folder, file.name))}`, file);
      form.reset();
    }, t("files.uploaded", { name: file.name }));
  };

  const newFolder = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const form = event.currentTarget;
    const name = String(new FormData(form).get("folder") ?? "").trim();
    if (!id || !name || BAD_NAME.test(name)) {
      setMessage({ kind: "error", text: t("error.invalid-path") });
      return;
    }
    void run(async () => {
      await api("/api/panel/files/folder", { body: { service: id, path: join(folder, name) } });
      form.reset();
    }, t("files.folder-created", { name }));
  };

  const remove = (entry: Entry) => {
    if (!id || !window.confirm(t("files.confirm-delete", { name: entry.name }))) return;
    void run(async () => {
      await api("/api/panel/files/delete", { body: { service: id, path: join(folder, entry.name) } });
    }, t("files.deleted", { name: entry.name }));
  };

  if (hidden || id === null) return <p role="status" className="text-muted">{t("start.loading")}</p>;
  if (!can("files:read")) return <Notice kind="error">{t("error.missing-right")}</Notice>;
  if (!id) return <Notice kind="error">{t("console.no-service")}</Notice>;

  const parts = folder ? folder.split("/") : [];
  return (
    <section aria-labelledby="files-title" className="flex flex-col gap-5">
      <p><a href="/services/" className="text-primary underline underline-offset-4">{t("services.back")}</a></p>
      <h1 id="files-title" ref={heading} tabIndex={-1} className="font-display text-4xl">
        {t("files.title")} <span className="font-mono text-2xl">{id}</span>
      </h1>
      <nav aria-label={t("files.breadcrumb")}>
        <ol className="flex flex-wrap items-center gap-1 font-mono">
          <li>
            <button type="button" onClick={() => goTo("")} aria-current={parts.length === 0 ? "location" : undefined}
              className="min-h-11 px-1 text-primary underline underline-offset-4">{id}</button>
          </li>
          {parts.map((part, index) => (
            <li key={index} className="flex items-center gap-1">
              <span aria-hidden="true">/</span>
              <button type="button" onClick={() => goTo(parts.slice(0, index + 1).join("/"))}
                aria-current={index === parts.length - 1 ? "location" : undefined}
                className="min-h-11 px-1 text-primary underline underline-offset-4">{part}</button>
            </li>
          ))}
        </ol>
      </nav>
      {message && <Notice kind={message.kind}>{message.text}</Notice>}

      {open ? (
        <form onSubmit={save} className="flex flex-col gap-3">
          <label htmlFor="file-editor" className="font-bold">
            {t("files.editing")} <span className="font-mono">{open.path}</span>
          </label>
          <textarea id="file-editor" ref={editor} defaultValue={open.text} key={open.path + open.text.length}
            readOnly={!write} spellCheck={false} rows={24}
            className="border border-line bg-sunken p-3 font-mono text-sm text-ink" />
          <div className="flex flex-wrap gap-3">
            {write && <Button type="submit" disabled={busy}>{t("files.save")}</Button>}
            <Button variant="plain" type="button" onClick={() => setOpen(null)}>{t("files.close")}</Button>
          </div>
        </form>
      ) : entries === null ? (
        <p role="status" className="text-muted">{t("start.loading")}</p>
      ) : (
        <div className="overflow-x-auto border border-line">
          <table className="w-full border-collapse text-left">
            <caption className="sr-only">{t("files.caption", { path: folder || "/" })}</caption>
            <thead className="bg-raised">
              <tr>
                <th scope="col" className="px-3 py-2">{t("files.name")}</th>
                <th scope="col" className="px-3 py-2">{t("files.size")}</th>
                <th scope="col" className="px-3 py-2">{t("files.modified")}</th>
                <th scope="col" className="px-3 py-2">{t("services.actions")}</th>
              </tr>
            </thead>
            <tbody>
              {parts.length > 0 && (
                <tr className="border-t border-line">
                  <td className="px-3 py-2" colSpan={4}>
                    <button type="button" onClick={() => goTo(parts.slice(0, -1).join("/"))}
                      className="min-h-11 text-primary underline underline-offset-4">{t("files.up")}</button>
                  </td>
                </tr>
              )}
              {entries.length === 0 && (
                <tr className="border-t border-line"><td className="px-3 py-2" colSpan={4}>{t("files.empty")}</td></tr>
              )}
              {entries.map((entry) => (
                <tr key={entry.name} className="border-t border-line">
                  <th scope="row" className="px-3 py-2 font-mono font-normal">
                    {entry.folder ? (
                      <button type="button" onClick={() => goTo(join(folder, entry.name))}
                        className="min-h-11 text-left text-primary underline underline-offset-4">
                        <span aria-hidden="true">▸ </span>{entry.name}<span className="sr-only"> ({t("files.folder")})</span>
                      </button>
                    ) : (
                      <button type="button" onClick={() => void openFile(entry)}
                        className="min-h-11 text-left text-primary underline underline-offset-4">{entry.name}</button>
                    )}
                  </th>
                  <td className="px-3 py-2">{entry.folder ? "–" : t("files.bytes", { n: formatNumber(entry.size) })}</td>
                  <td className="px-3 py-2">{formatDate(entry.modified)}</td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-2">
                      {!entry.folder && (
                        <a href={`/api/panel/files/content?${query(id, join(folder, entry.name))}`} download={entry.name}
                          className="flex min-h-11 items-center px-2 text-primary underline underline-offset-4">
                          {t("files.download")}<span className="sr-only"> {entry.name}</span>
                        </a>
                      )}
                      {write && (
                        <Button variant="danger" disabled={busy} onClick={() => remove(entry)}>
                          {t("files.delete")}<span className="sr-only"> {entry.name}</span>
                        </Button>
                      )}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {write && !open && (
        <div className="grid gap-4 md:grid-cols-2">
          <form onSubmit={sendFiles} className="flex flex-col gap-2 border border-line bg-raised p-4" noValidate>
            <label htmlFor="file-upload" className="font-bold">{t("files.upload")}</label>
            <p id="file-upload-hint" className="text-sm text-muted">{t("files.upload-hint")}</p>
            <input id="file-upload" name="upload" type="file" aria-describedby="file-upload-hint"
              className="min-h-11 text-ink file:mr-3 file:min-h-11 file:border-2 file:border-line file:bg-raised file:px-3 file:font-bold" />
            <Button type="submit" disabled={busy} className="self-start">{t("files.upload-send")}</Button>
          </form>
          <form onSubmit={newFolder} className="flex flex-col gap-2 border border-line bg-raised p-4" noValidate>
            <label htmlFor="file-folder" className="font-bold">{t("files.new-folder")}</label>
            <input id="file-folder" name="folder" maxLength={255} autoComplete="off"
              className="min-h-11 border border-line bg-sunken px-3 font-mono text-ink" />
            <Button type="submit" disabled={busy} className="self-start">{t("files.create")}</Button>
          </form>
        </div>
      )}
    </section>
  );
}
