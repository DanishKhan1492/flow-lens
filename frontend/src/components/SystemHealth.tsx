"use client";

import { useEffect, useRef, useState } from "react";
import type { TraceRecord, ConnectionState } from "@/types/trace";
import clsx from "clsx";

interface Props {
  connectionState: ConnectionState;
  traces:          TraceRecord[];
}

const STATE_LABEL: Record<ConnectionState, string> = {
  connected:    "LIVE",
  connecting:   "CONNECTING",
  disconnected: "OFFLINE",
};

const STATE_COLOR: Record<ConnectionState, string> = {
  connected:    "var(--neon-cyan)",
  connecting:   "var(--warn-orange)",
  disconnected: "var(--text-dim)",
};

function MetricRow({ label, value, valueColor }: {
  label:       string;
  value:       string | number;
  valueColor?: string;
}) {
  return (
    <div className="flex items-center justify-between py-1.5"
      style={{ borderBottom: "1px solid var(--border-subtle)" }}>
      <span className="text-[10px] tracking-widest uppercase" style={{ color: "var(--text-muted)" }}>
        {label}
      </span>
      <span
        className="font-mono text-xs tabular-nums font-medium"
        style={{ color: valueColor ?? "var(--text-primary)" }}
      >
        {value}
      </span>
    </div>
  );
}

export default function SystemHealth({ connectionState, traces }: Props) {
  const [tps, setTps] = useState(0);
  const traceTimesRef = useRef<number[]>([]);

  useEffect(() => {
    if (traces.length > 0) {
      traceTimesRef.current.push(Date.now());
    }
  }, [traces.length]);

  useEffect(() => {
    const interval = setInterval(() => {
      const now = Date.now();
      traceTimesRef.current = traceTimesRef.current.filter((t) => now - t < 5_000);
      setTps(Math.round(traceTimesRef.current.length / 5));
    }, 1_000);
    return () => clearInterval(interval);
  }, []);

  const errorCount  = traces.filter((t) => t.error).length;
  const avgDuration = traces.length > 0
    ? Math.round(traces.slice(0, 50).reduce((s, t) => s + t.durationMs, 0) / Math.min(traces.length, 50))
    : 0;

  return (
    <div className="glass-panel p-4 flex flex-col gap-2">
      {/* Header */}
      <div className="flex items-center justify-between mb-1">
        <p className="font-display text-[10px] tracking-[0.2em] uppercase"
           style={{ color: "var(--neon-cyan)" }}>
          System Health
        </p>
        {/* Live status indicator */}
        <div className="flex items-center gap-1.5">
          <span
            className={clsx("inline-block w-2 h-2 rounded-full", {
              "dot-connected":    connectionState === "connected",
              "dot-connecting":   connectionState === "connecting",
              "dot-disconnected": connectionState === "disconnected",
            })}
          />
          <span
            className="font-mono text-[10px] tracking-widest"
            style={{ color: STATE_COLOR[connectionState] }}
          >
            {STATE_LABEL[connectionState]}
          </span>
        </div>
      </div>

      {/* Metrics */}
      <MetricRow
        label="Traces Captured"
        value={traces.length.toLocaleString()}
        valueColor="var(--neon-cyan)"
      />
      <MetricRow
        label="Throughput"
        value={`${tps} t/s`}
        valueColor={tps > 50 ? "var(--warn-orange)" : undefined}
      />
      <MetricRow label="Unique Traces" value={traces.length} />
      <MetricRow
        label="Avg Duration (50)"
        value={`${avgDuration} ms`}
        valueColor={avgDuration > 200 ? "var(--warn-orange)" : undefined}
      />
      <MetricRow
        label="Errors"
        value={errorCount}
        valueColor={errorCount > 0 ? "var(--danger)" : "var(--success)"}
      />
    </div>
  );
}
