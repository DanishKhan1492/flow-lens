"use client";

import { useRef, useEffect, useState } from "react";
import clsx from "clsx";
import type { TraceRecord } from "@/types/trace";

interface Props {
  traces: TraceRecord[];
}

function shortName(fqn: string): string {
  if (!fqn) return "—";
  const parts = fqn.split(".");
  return parts[parts.length - 1];
}

function formatTime(ts: string): string {
  try {
    return new Date(ts).toLocaleTimeString("en-GB", {
      hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
    });
  } catch {
    return ts.slice(11, 19) || "—";
  }
}

function durationStyle(ms: number): React.CSSProperties {
  if (ms < 10)  return { color: "var(--text-muted)" };
  if (ms < 100) return { color: "var(--text-primary)" };
  if (ms < 500) return { color: "var(--warn-orange)" };
  return { color: "var(--danger)" };
}

export default function TraceLogs({ traces }: Props) {
  const prevLengthRef = useRef(0);
  const [newCount, setNewCount] = useState(0);

  useEffect(() => {
    const delta = traces.length - prevLengthRef.current;
    if (delta > 0) {
      setNewCount(delta);
      const t = setTimeout(() => setNewCount(0), 1400);
      return () => clearTimeout(t);
    }
    prevLengthRef.current = traces.length;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [traces.length]);

  useEffect(() => {
    prevLengthRef.current = traces.length;
  }, [traces.length]);

  return (
    <div className="glass-panel flex flex-col h-full overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-3 shrink-0"
           style={{ borderBottom: "1px solid var(--border-subtle)" }}>
        <span className="font-display text-[10px] tracking-[0.2em] uppercase"
              style={{ color: "var(--neon-cyan)" }}>Trace Log</span>
        <span className="font-mono text-[10px] tabular-nums" style={{ color: "var(--text-dim)" }}>
          {traces.length.toLocaleString()} traces
        </span>
      </div>

      {/* Column headers */}
      <div className="grid px-4 py-1.5 shrink-0 text-[10px] tracking-widest uppercase"
           style={{ gridTemplateColumns: "56px 48px 1fr 44px", borderBottom: "1px solid var(--border-subtle)", color: "var(--text-dim)" }}>
        <span>Time</span>
        <span>Type</span>
        <span>Label</span>
        <span className="text-right">ms</span>
      </div>

      {/* Scrollable rows */}
      <div className="flex-1 overflow-y-auto">
        {traces.length === 0 ? (
          <div className="flex items-center justify-center h-20 text-[11px]"
               style={{ color: "var(--text-dim)" }}>Waiting for traces…</div>
        ) : (
          traces.map((trace, index) => (
            <div key={trace.id}
                 className={clsx("grid px-4 py-1 text-[11px] transition-colors select-text cursor-default",
                   index < newCount && "animate-[row-flash_1.2s_ease-out_forwards]")}
                 style={{ gridTemplateColumns: "56px 48px 1fr 44px", borderBottom: "1px solid rgba(0,243,255,0.04)" }}>
              <span style={{ color: "var(--text-dim)" }} className="tabular-nums truncate">
                {formatTime(trace.timestamp)}
              </span>
              <span className="font-mono text-[9px] truncate"
                    style={{ color: trace.entryPointType === "API" ? "var(--neon-cyan)" : trace.entryPointType === "CONSUMER" ? "#b97cff" : "var(--warn-orange)" }}>
                {trace.entryPointType}
              </span>
              <span style={{ color: "var(--text-primary)" }} className="truncate">
                {trace.error && <span style={{ color: "var(--danger)" }} className="mr-1">⚠</span>}
                {trace.label}
              </span>
              <span className="text-right tabular-nums" style={durationStyle(trace.durationMs)}>
                {trace.durationMs}
              </span>
            </div>
          ))
        )}
      </div>
    </div>
  );
}
