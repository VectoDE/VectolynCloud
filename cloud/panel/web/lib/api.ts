// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later

/** An error answer of the panel: HTTP status, stable code, message of the server. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly body: Record<string, unknown> = {},
  ) {
    super(message);
  }
}

let csrfToken: string | null = null;

/** Stores the CSRF token of the session; every changing request sends it in X-Vectolyn-CSRF. */
export function setCsrf(token: string | null) {
  csrfToken = token;
}

/**
 * Calls an endpoint of the panel. Changing requests carry the CSRF header; before sign-in it holds
 * a placeholder, which the server accepts only on the sign-in endpoints.
 */
export async function api<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = init.method ?? (init.body === undefined ? "GET" : "POST");
  const headers: Record<string, string> = { Accept: "application/json" };
  if (method !== "GET") {
    headers["X-Vectolyn-CSRF"] = csrfToken ?? "1";
    headers["Content-Type"] = "application/json";
  }
  const response = await fetch(path, {
    method,
    headers,
    credentials: "same-origin",
    body: init.body === undefined ? undefined : JSON.stringify(init.body),
  });
  const text = await response.text();
  let json: Record<string, unknown> = {};
  try {
    json = text ? (JSON.parse(text) as Record<string, unknown>) : {};
  } catch {
    // not JSON: handled below
  }
  if (!response.ok) {
    throw new ApiError(response.status, String(json.code ?? "http-" + response.status), String(json.message ?? ""), json);
  }
  if (typeof json.csrf === "string") setCsrf(json.csrf);
  return json as T;
}

async function fail(response: Response): Promise<never> {
  let json: Record<string, unknown> = {};
  try {
    json = (await response.json()) as Record<string, unknown>;
  } catch {
    // not JSON
  }
  throw new ApiError(response.status, String(json.code ?? "http-" + response.status), String(json.message ?? ""), json);
}

/** Sends raw bytes (a file upload or an archive); returns the JSON answer. */
export async function upload<T = Record<string, unknown>>(path: string, content: Blob | string): Promise<T> {
  const response = await fetch(path, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/octet-stream", "X-Vectolyn-CSRF": csrfToken ?? "1" },
    credentials: "same-origin",
    body: content,
  });
  if (!response.ok) await fail(response);
  return (await response.json()) as T;
}

/** A document of the API with its ETag, for an edit that must not overwrite someone else's. */
export async function getDocument<T>(path: string): Promise<{ body: T; etag: string | null }> {
  const response = await fetch(path, { headers: { Accept: "application/json" }, credentials: "same-origin" });
  if (!response.ok) await fail(response);
  return { body: (await response.json()) as T, etag: response.headers.get("ETag") };
}

/** Writes a document with If-Match; returns the new ETag. */
export async function putDocument(path: string, body: unknown, etag: string | null): Promise<string | null> {
  const headers: Record<string, string> = {
    Accept: "application/json",
    "Content-Type": "application/json",
    "X-Vectolyn-CSRF": csrfToken ?? "1",
  };
  if (etag) headers["If-Match"] = etag;
  const response = await fetch(path, { method: "PUT", headers, credentials: "same-origin", body: JSON.stringify(body) });
  if (!response.ok) await fail(response);
  return response.headers.get("ETag");
}

/** Reads raw bytes (a file of a service). */
export async function download(path: string): Promise<ArrayBuffer> {
  const response = await fetch(path, { credentials: "same-origin" });
  if (!response.ok) await fail(response);
  return response.arrayBuffer();
}

/** A service of the node (GET /api/v1/services). */
export interface Service {
  id: string;
  group: string;
  task: string;
  index: number;
  mode: string;
  state: string;
  port: number;
  "players-online"?: number;
  "max-players"?: number;
  "started-at"?: string;
}

export interface PanelInfo {
  name: string;
  version: string;
  frontend: boolean;
  "setup-required": boolean;
  oidc: string | null;
}

export interface Passkey {
  id: string;
  label: string;
  "created-at": string;
}

export interface Session {
  user: string;
  roles: string[];
  rights: string[];
  csrf: string;
  method: string;
  totp: boolean;
  passkeys: Passkey[];
  "oidc-links": { issuer: string; subject: string }[];
  oidc: string | null;
}
