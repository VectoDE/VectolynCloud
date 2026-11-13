// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Button, Notice, Panel, useErrorText } from "@/components/Form";
import { api, ApiError, getDocument, putDocument, upload } from "@/lib/api";
import { useAuth, useGuard } from "@/lib/auth";
import { useI18n, type MessageKey } from "@/lib/i18n";

type Message = { kind: "ok" | "error"; text: string; details?: string[] } | null;

/** The field errors of a 422 or 400 answer, as lines for the user. */
function details(error: unknown): string[] | undefined {
  if (!(error instanceof ApiError) || !Array.isArray(error.body.errors)) return undefined;
  return (error.body.errors as Record<string, unknown>[]).map((e) =>
    [e.file, e.path, e.code, e.message].filter((part) => typeof part === "string" && part).join(" · "),
  );
}

function Feedback({ message }: { message: Message }) {
  if (!message) return null;
  return (
    <Notice kind={message.kind}>
      {message.text}
      {message.details && message.details.length > 0 && (
        <ul className="mt-2 list-disc pl-5 font-normal">
          {message.details.map((line, i) => <li key={i} className="font-mono text-sm">{line}</li>)}
        </ul>
      )}
    </Notice>
  );
}

/** Groups and tasks: the document as JSON, saved with If-Match through the API's validation. */
function DocumentEditor({ kind }: { kind: "groups" | "tasks" }) {
  const { t } = useI18n();
  const { can } = useAuth();
  const errorText = useErrorText();
  const [names, setNames] = useState<string[] | null>(null);
  const [open, setOpen] = useState<{ name: string; text: string; etag: string | null } | null>(null);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const editor = useRef<HTMLTextAreaElement>(null);
  const write = can("config:write");

  useEffect(() => {
    api<Record<string, unknown>>(`/api/v1/${kind}`)
      .then((all) => setNames(Object.keys(all).sort()))
      .catch((error) => {
        setNames([]);
        setMessage({ kind: "error", text: errorText(error) });
      });
  }, [kind, errorText]);

  const load = async (name: string) => {
    setMessage(null);
    try {
      const doc = await getDocument<unknown>(`/api/v1/${kind}/${encodeURIComponent(name)}`);
      setOpen({ name, text: JSON.stringify(doc.body, null, 2), etag: doc.etag });
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    }
  };

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!open) return;
    const text = editor.current?.value ?? "";
    let body: unknown;
    try {
      body = JSON.parse(text);
    } catch {
      setMessage({ kind: "error", text: t("config.not-json") });
      return;
    }
    setBusy(true);
    setMessage(null);
    try {
      const etag = await putDocument(`/api/v1/${kind}/${encodeURIComponent(open.name)}`, body, open.etag);
      setOpen({ name: open.name, text, etag });
      setMessage({ kind: "ok", text: t("files.saved", { name: open.name }) });
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error), details: details(error) });
    } finally {
      setBusy(false);
    }
  };

  const id = `config-${kind}`;
  return (
    <Panel id={id} title={t(kind === "groups" ? "config.groups" : "config.tasks")}>
      <Feedback message={message} />
      {names === null ? (
        <p role="status" className="text-muted">{t("start.loading")}</p>
      ) : names.length === 0 ? (
        <p>{t("config.none")}</p>
      ) : (
        <ul className="flex flex-wrap gap-2" aria-label={t(kind === "groups" ? "config.groups" : "config.tasks")}>
          {names.map((name) => (
            <li key={name}>
              <Button variant="plain" aria-pressed={open?.name === name} onClick={() => void load(name)}>{name}</Button>
            </li>
          ))}
        </ul>
      )}
      {open && (
        <form onSubmit={save} className="flex flex-col gap-2">
          <label htmlFor={`${id}-editor`} className="font-bold">
            {t("files.editing")} <span className="font-mono">{open.name}</span>
          </label>
          <p id={`${id}-hint`} className="text-sm text-muted">{t("config.document-hint")}</p>
          <textarea id={`${id}-editor`} ref={editor} key={open.name + (open.etag ?? "")} defaultValue={open.text}
            readOnly={!write} rows={16} spellCheck={false} aria-describedby={`${id}-hint`}
            className="border border-line bg-sunken p-3 font-mono text-sm text-ink" />
          <div className="flex flex-wrap gap-3">
            {write && <Button type="submit" disabled={busy}>{t("files.save")}</Button>}
            <Button variant="plain" type="button" onClick={() => setOpen(null)}>{t("files.close")}</Button>
          </div>
        </form>
      )}
    </Panel>
  );
}

interface TemplateFile {
  path: string;
  size: number;
}

/** Template files: listed by the API, text files edited with If-Match. */
function TemplateEditor() {
  const { t } = useI18n();
  const { can } = useAuth();
  const errorText = useErrorText();
  const [templates, setTemplates] = useState<string[] | null>(null);
  const [template, setTemplate] = useState<string | null>(null);
  const [files, setFiles] = useState<TemplateFile[] | null>(null);
  const [open, setOpen] = useState<{ path: string; text: string; etag: string | null } | null>(null);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);
  const editor = useRef<HTMLTextAreaElement>(null);
  const write = can("templates:write") && can("files:write");

  useEffect(() => {
    api<{ name: string }[]>("/api/v1/templates")
      .then((list) => setTemplates(list.map((x) => x.name)))
      .catch((error) => {
        setTemplates([]);
        setMessage({ kind: "error", text: errorText(error) });
      });
  }, [errorText]);

  const choose = async (name: string) => {
    setTemplate(name);
    setOpen(null);
    setFiles(null);
    setMessage(null);
    try {
      setFiles((await api<{ files: TemplateFile[] }>(`/api/v1/templates/${encodeURIComponent(name)}/files`)).files);
    } catch (error) {
      setFiles([]);
      setMessage({ kind: "error", text: errorText(error) });
    }
  };

  const load = async (path: string) => {
    if (!template) return;
    setMessage(null);
    try {
      const doc = await getDocument<{ encoding: string; content: string }>(
        `/api/v1/templates/${encodeURIComponent(template)}/files/${path}`);
      if (doc.body.encoding !== "utf-8") {
        setMessage({ kind: "error", text: t("files.not-text") });
        return;
      }
      setOpen({ path, text: doc.body.content, etag: doc.etag });
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    }
  };

  const save = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!template || !open) return;
    const text = editor.current?.value ?? "";
    setBusy(true);
    setMessage(null);
    try {
      const etag = await putDocument(`/api/v1/templates/${encodeURIComponent(template)}/files/${open.path}`,
        { content: text, encoding: "utf-8" }, open.etag);
      setOpen({ path: open.path, text, etag });
      setMessage({ kind: "ok", text: t("files.saved", { name: open.path }) });
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error), details: details(error) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel id="config-templates" title={t("config.templates")}>
      <p className="text-muted">{t("config.templates-lede")}</p>
      <Feedback message={message} />
      {templates && templates.length > 0 && (
        <ul className="flex flex-wrap gap-2" aria-label={t("config.templates")}>
          {templates.map((name) => (
            <li key={name}>
              <Button variant="plain" aria-pressed={template === name} onClick={() => void choose(name)}>{name}</Button>
            </li>
          ))}
        </ul>
      )}
      {template && files && !open && (
        files.length === 0 ? <p>{t("files.empty")}</p> : (
          <ul className="flex max-h-80 flex-col overflow-y-auto border border-line" aria-label={t("config.template-files", { name: template })}>
            {files.map((file) => (
              <li key={file.path} className="border-b border-line last:border-b-0">
                <button type="button" onClick={() => void load(file.path)}
                  className="min-h-11 w-full px-3 text-left font-mono text-primary underline underline-offset-4">
                  {file.path}
                </button>
              </li>
            ))}
          </ul>
        )
      )}
      {open && (
        <form onSubmit={save} className="flex flex-col gap-2">
          <label htmlFor="template-editor" className="font-bold">
            {t("files.editing")} <span className="font-mono">{open.path}</span>
          </label>
          <textarea id="template-editor" ref={editor} key={open.path + (open.etag ?? "")} defaultValue={open.text}
            readOnly={!write} rows={16} spellCheck={false}
            className="border border-line bg-sunken p-3 font-mono text-sm text-ink" />
          <div className="flex flex-wrap gap-3">
            {write && <Button type="submit" disabled={busy}>{t("files.save")}</Button>}
            <Button variant="plain" type="button" onClick={() => setOpen(null)}>{t("files.close")}</Button>
          </div>
        </form>
      )}
    </Panel>
  );
}

interface ImportPreview {
  files: { file: string; kind: string }[];
  diff: string;
  confirm: string;
}

/** Export as a download; import as preview first, then the confirmed apply of that same archive. */
function Transfer() {
  const { t } = useI18n();
  const { can } = useAuth();
  const errorText = useErrorText();
  const [archive, setArchive] = useState<File | null>(null);
  const [preview, setPreview] = useState<ImportPreview | null>(null);
  const [message, setMessage] = useState<Message>(null);
  const [busy, setBusy] = useState(false);

  const check = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const file = (event.currentTarget.elements.namedItem("archive") as HTMLInputElement).files?.[0];
    if (!file) {
      setMessage({ kind: "error", text: t("files.choose-file") });
      return;
    }
    setBusy(true);
    setMessage(null);
    setPreview(null);
    try {
      setPreview(await upload<ImportPreview>("/api/panel/config/import/preview", file));
      setArchive(file);
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error), details: error instanceof ApiError && error.message ? [error.message] : undefined });
    } finally {
      setBusy(false);
    }
  };

  const apply = async () => {
    if (!archive || !preview) return;
    setBusy(true);
    setMessage(null);
    try {
      await upload(`/api/panel/config/import?confirm=${preview.confirm}`, archive);
      setMessage({ kind: "ok", text: t("config.imported", { count: preview.files.length }) });
      setPreview(null);
      setArchive(null);
    } catch (error) {
      setMessage({ kind: "error", text: errorText(error) });
    } finally {
      setBusy(false);
    }
  };

  return (
    <Panel id="config-transfer" title={t("config.transfer")}>
      <p className="text-muted">{t("config.transfer-lede")}</p>
      <Feedback message={message} />
      <p>
        <a href="/api/panel/config/export" download="vectolyn-config.zip"
          className="inline-flex min-h-11 items-center border-2 border-line bg-raised px-4 font-bold text-ink">
          {t("config.export")}
        </a>
      </p>
      {can("config:write") && (
        <form onSubmit={check} className="flex flex-col gap-2" noValidate>
          <label htmlFor="config-archive" className="font-bold">{t("config.import")}</label>
          <p id="config-archive-hint" className="text-sm text-muted">{t("config.import-hint")}</p>
          <input id="config-archive" name="archive" type="file" accept=".zip,application/zip" aria-describedby="config-archive-hint"
            className="min-h-11 text-ink file:mr-3 file:min-h-11 file:border-2 file:border-line file:bg-raised file:px-3 file:font-bold" />
          <Button type="submit" disabled={busy} className="self-start">{t("config.preview")}</Button>
        </form>
      )}
      {preview && (
        <div className="flex flex-col gap-3 border-2 border-primary p-4">
          <h3 className="font-display text-xl">{t("config.preview-title")}</h3>
          {preview.files.length === 0 ? (
            <p>{t("config.no-changes")}</p>
          ) : (
            <>
              <ul className="list-disc pl-5">
                {preview.files.map((f) => (
                  <li key={f.file}><span className="font-mono">{f.file}</span> – {t(`config.kind-${f.kind}` as MessageKey)}</li>
                ))}
              </ul>
              <pre tabIndex={0} aria-label={t("config.diff")}
                className="max-h-96 overflow-auto border border-line bg-sunken p-3 font-mono text-sm">{preview.diff}</pre>
              <Button variant="danger" disabled={busy} onClick={() => void apply()} className="self-start">
                {t("config.apply", { count: preview.files.length })}
              </Button>
            </>
          )}
        </div>
      )}
    </Panel>
  );
}

export default function ConfigPage() {
  const { t } = useI18n();
  const { can } = useAuth();
  const hidden = useGuard("private");
  if (hidden) return <p role="status" className="text-muted">{t("start.loading")}</p>;
  if (!can("config:read") && !can("templates:read")) return <Notice kind="error">{t("error.missing-right")}</Notice>;
  return (
    <section aria-labelledby="config-title" className="flex flex-col gap-6">
      <h1 id="config-title" className="font-display text-4xl">{t("config.title")}</h1>
      <p className="max-w-3xl">{t("config.lede")}</p>
      {can("config:read") && <DocumentEditor kind="groups" />}
      {can("config:read") && <DocumentEditor kind="tasks" />}
      {can("templates:read") && can("files:read") && <TemplateEditor />}
      {can("config:read") && <Transfer />}
    </section>
  );
}
