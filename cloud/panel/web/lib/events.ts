// SPDX-FileCopyrightText: 2026 VectoDE
// SPDX-License-Identifier: GPL-3.0-or-later
"use client";

import { useEffect, useRef, useState } from "react";

/** One live event of the node, as the API sends it. */
export interface NodeEvent {
  type: string;
  time: string;
  data: Record<string, unknown>;
}

export type StreamState = "connecting" | "open" | "closed";

/**
 * Follows the node's live events over the panel's WebSocket. The browser sends the session cookie
 * and its origin; the server checks both. After a drop it connects again, waiting 1, 2, 4 ... up to
 * 30 seconds. A close with 1008 (rights or session ended) is final.
 */
export function useEvents(
  types: string[],
  services: string[],
  onEvent: (event: NodeEvent) => void,
  enabled = true,
): StreamState {
  const [state, setState] = useState<StreamState>("connecting");
  const handler = useRef(onEvent);
  useEffect(() => {
    handler.current = onEvent;
  }, [onEvent]);
  const typeKey = types.join(",");
  const serviceKey = services.join(",");

  useEffect(() => {
    if (!enabled) return;
    let socket: WebSocket | null = null;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let delay = 1000;
    let stopped = false;

    const connect = () => {
      const query = new URLSearchParams();
      if (typeKey) query.set("types", typeKey);
      if (serviceKey) query.set("services", serviceKey);
      const scheme = window.location.protocol === "https:" ? "wss" : "ws";
      setState("connecting");
      socket = new WebSocket(`${scheme}://${window.location.host}/api/panel/events?${query.toString()}`);
      socket.onopen = () => {
        delay = 1000;
        setState("open");
      };
      socket.onmessage = (message) => {
        try {
          handler.current(JSON.parse(String(message.data)) as NodeEvent);
        } catch {
          // not an event: ignored, as the API asks clients to do with unknown content
        }
      };
      socket.onclose = (close) => {
        setState("closed");
        if (stopped || close.code === 1008) return;
        timer = setTimeout(connect, delay);
        delay = Math.min(delay * 2, 30000);
      };
    };
    connect();
    return () => {
      stopped = true;
      if (timer) clearTimeout(timer);
      socket?.close(1000);
    };
  }, [typeKey, serviceKey, enabled]);

  return state;
}
