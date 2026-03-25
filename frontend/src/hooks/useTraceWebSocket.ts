"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import type { TraceRecord, ConnectionState } from "@/types/trace";

const MAX_RECORDS    = 100;
const INITIAL_DELAY  = 500;
const MAX_DELAY      = 30_000;

interface UseTraceWebSocketReturn {
  traces:          TraceRecord[];
  connectionState: ConnectionState;
  clearTraces:     () => void;
}

/**
 * Maintains a persistent WebSocket connection to the FlowLens starter
 * embedded in the target Spring Boot application.
 *
 * Incoming messages:
 *   {"type":"trace","record":{...}}  — new TraceRecord completed
 *   {"type":"clear"}                 — all traces deleted
 *   {"type":"pong"}                  — heartbeat response (ignored)
 *
 * On connect, hydrates the list from GET /flow-lens/api/traces so the
 * dashboard is immediately populated with any recorded history.
 */
export function useTraceWebSocket(wsUrl: string): UseTraceWebSocketReturn {
  const [traces,          setTraces]          = useState<TraceRecord[]>([]);
  const [connectionState, setConnectionState] = useState<ConnectionState>("connecting");

  const clearTraces = useCallback(() => setTraces([]), []);

  // All mutable state lives in refs so the effect closure stays stable
  const activeRef  = useRef(true);
  const delayRef   = useRef(INITIAL_DELAY);
  const timerRef   = useRef<ReturnType<typeof setTimeout> | null>(null);
  const socketRef  = useRef<WebSocket | null>(null);
  const pingRef    = useRef<ReturnType<typeof setInterval> | null>(null);

  // Derive the REST base URL from the WS URL:
  //   ws://localhost:8009/flow-lens/ws  →  http://localhost:8009
  const restBase = wsUrl
    .replace(/^ws:\/\//, "http://")
    .replace(/^wss:\/\//, "https://")
    .replace(/\/flow-lens\/ws$/, "");

  const fetchHistory = useCallback(async () => {
    try {
      const res = await fetch(`${restBase}/flow-lens/api/traces`);
      if (!res.ok) return;
      const data = (await res.json()) as TraceRecord[];
      if (data.length === 0) return;
      setTraces(prev => {
        const existingIds = new Set(prev.map(r => r.id));
        const fresh = data.filter(r => !existingIds.has(r.id));
        return [...fresh, ...prev].slice(0, MAX_RECORDS);
      });
    } catch {
      // Backend not ready yet — non-fatal
    }
  }, [restBase]);

  useEffect(() => {
    activeRef.current = true;
    delayRef.current  = INITIAL_DELAY;

    const connect = () => {
      if (!activeRef.current) return;
      setConnectionState("connecting");

      const ws = new WebSocket(wsUrl);
      socketRef.current = ws;

      ws.onopen = () => {
        if (!activeRef.current) { ws.close(); return; }
        setConnectionState("connected");
        delayRef.current = INITIAL_DELAY;
        void fetchHistory();
        pingRef.current = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: "ping" }));
          }
        }, 25_000);
      };

      ws.onmessage = (event) => {
        if (!activeRef.current) return;
        try {
          const msg = JSON.parse(event.data as string) as Record<string, unknown>;
          if (msg.type === "pong" || msg.type === "ping") return;
          if (msg.type === "clear")  { setTraces([]); return; }
          if (msg.type === "trace" && msg.record) {
            const record = msg.record as TraceRecord;
            setTraces(prev => [record, ...prev].slice(0, MAX_RECORDS));
          }
        } catch {
          // Discard malformed frames
        }
      };

      ws.onclose = () => {
        if (pingRef.current) { clearInterval(pingRef.current); pingRef.current = null; }
        if (!activeRef.current) return;
        setConnectionState("disconnected");
        const delay = delayRef.current;
        delayRef.current = Math.min(delay * 2, MAX_DELAY);
        timerRef.current = setTimeout(connect, delay);
      };

      ws.onerror = () => { ws.close(); };
    };

    connect();

    return () => {
      activeRef.current = false;
      if (timerRef.current)  clearTimeout(timerRef.current);
      if (pingRef.current)   clearInterval(pingRef.current);
      if (socketRef.current) {
        socketRef.current.onclose = null;
        socketRef.current.close();
      }
    };
  }, [wsUrl, fetchHistory]);

  return { traces, connectionState, clearTraces };
}
