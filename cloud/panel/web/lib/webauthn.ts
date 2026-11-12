// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later

/** Base64url without padding, as the panel sends and expects binary values. */
export function toB64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let text = "";
  for (const b of bytes) text += String.fromCharCode(b);
  return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

export function fromB64(text: string): ArrayBuffer {
  const padded = text.replace(/-/g, "+").replace(/_/g, "/") + "===".slice((text.length + 3) % 4);
  const raw = atob(padded);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  return bytes.buffer;
}

export function passkeysSupported(): boolean {
  return typeof window !== "undefined" && typeof window.PublicKeyCredential === "function";
}

interface CreateOptions {
  ticket: string;
  challenge: string;
  rp: { id: string; name: string };
  user: { id: string; name: string; "display-name": string };
  algorithms: number[];
  exclude: string[];
  timeout: number;
}

/** Runs navigator.credentials.create with the panel's options; returns the body for the server. */
export async function createPasskey(options: CreateOptions, label: string) {
  const credential = (await navigator.credentials.create({
    publicKey: {
      challenge: fromB64(options.challenge),
      rp: { id: options.rp.id, name: options.rp.name },
      user: { id: fromB64(options.user.id), name: options.user.name, displayName: options.user["display-name"] },
      pubKeyCredParams: options.algorithms.map((alg) => ({ type: "public-key" as const, alg })),
      excludeCredentials: options.exclude.map((id) => ({ type: "public-key" as const, id: fromB64(id) })),
      authenticatorSelection: { residentKey: "required", userVerification: "required" },
      attestation: "none",
      timeout: options.timeout,
    },
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error("cancelled");
  const response = credential.response as AuthenticatorAttestationResponse;
  return {
    ticket: options.ticket,
    label,
    "client-data-json": toB64(response.clientDataJSON),
    "attestation-object": toB64(response.attestationObject),
  };
}

/** Runs navigator.credentials.get for a passkey of any account (discoverable credentials). */
export async function getPasskey(options: { ticket: string; challenge: string; "rp-id": string; timeout: number }) {
  const credential = (await navigator.credentials.get({
    publicKey: {
      challenge: fromB64(options.challenge),
      rpId: options["rp-id"],
      userVerification: "required",
      timeout: options.timeout,
    },
  })) as PublicKeyCredential | null;
  if (!credential) throw new Error("cancelled");
  const response = credential.response as AuthenticatorAssertionResponse;
  return {
    ticket: options.ticket,
    id: toB64(credential.rawId),
    "client-data-json": toB64(response.clientDataJSON),
    "authenticator-data": toB64(response.authenticatorData),
    signature: toB64(response.signature),
  };
}
