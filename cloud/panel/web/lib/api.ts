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

/** Sends raw bytes (a file upload); the answer is JSON like {@link api}. */
export async function upload(path: string, content: Blob | string): Promise<void> {
  const response = await fetch(path, {
    method: "POST",
    headers: { Accept: "application/json", "Content-Type": "application/octet-stream", "X-Vectolyn-CSRF": csrfToken ?? "1" },
    credentials: "same-origin",
    body: content,
  });
  if (!response.ok) {
    let json: Record<string, unknown> = {};
    try {
      json = (await response.json()) as Record<string, unknown>;
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, String(json.code ?? "http-" + response.status), String(json.message ?? ""), json);
  }
}

/** Reads raw bytes (a file of a service). */
export async function download(path: string): Promise<ArrayBuffer> {
  const response = await fetch(path, { credentials: "same-origin" });
  if (!response.ok) {
    let json: Record<string, unknown> = {};
    try {
      json = (await response.json()) as Record<string, unknown>;
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, String(json.code ?? "http-" + response.status), String(json.message ?? ""), json);
  }
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
