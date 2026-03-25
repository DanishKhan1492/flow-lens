"use client";

import { useState } from "react";
import type { HttpResponse } from "@/types/trace";

interface Props {
  response: HttpResponse | null;
  sending:  boolean;
}

type RespTab = "body" | "headers";

function statusColor(code: number): string {
  if (code === 0)       return "var(--text-dim)";
  if (code < 300)       return "#22d47b";       // green
  if (code < 400)       return "var(--warn-orange)";
  return "var(--danger)";
}

function prettyJson(raw: string): string {
  try   { return JSON.stringify(JSON.parse(raw), null, 2); }
  catch { return raw; }
}

function durationColor(ms: number): string {
  if (ms < 100) return "#22d47b";
  if (ms < 500) return "var(--warn-orange)";
  return "var(--danger)";
}

export default function ResponsePanel({ response, sending }: Props) {
  const [tab, setTab] = useState<RespTab>("body");

  const tabStyle = (active: boolean): React.CSSProperties => ({
    fontFamily:    "var(--font-display, sans-serif)",
    fontSize:      "10px",
    letterSpacing: "0.15em",
    textTransform: "uppercase",
    padding:       "4px 10px",
    background:    "none",
    border:        "none",
    borderBottom:  active ? "2px solid var(--neon-cyan)" : "2px solid transparent",
    color:         active ? "var(--neon-cyan)" : "var(--text-dim)",
    cursor:        "pointer",
    transition:    "color 0.15s, border-color 0.15s",
  });

  // Empty / loading states
  if (sending) {
    return (
      <div className="glass-panel flex items-center justify-center h-full">
        <div style={{ textAlign: "center" }}>
          <div
            className="mx-auto mb-3"
            style={{
              width:       "24px",
              height:      "24px",
              border:      "2px solid var(--border-subtle)",
              borderTop:   "2px solid var(--neon-cyan)",
              borderRadius:"50%",
              animation:   "spin 0.8s linear infinite",
            }}
          />
          <p style={{ color: "var(--text-dim)", fontSize: "11px", letterSpacing: "0.1em" }}>
            WAITING FOR RESPONSE…
          </p>
        </div>
      </div>
    );
  }

  if (!response) {
    return (
      <div className="glass-panel flex flex-col items-center justify-center h-full gap-3">
        <svg width="32" height="32" viewBox="0 0 32 32" fill="none" aria-hidden="true">
          <circle cx="16" cy="16" r="13" stroke="var(--border-subtle)" strokeWidth="1.5" strokeDasharray="3 3" />
          <path d="M10 16h12M22 16l-4-4M22 16l-4 4" stroke="var(--text-dim)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>
        <p style={{ color: "var(--text-dim)", fontSize: "11px", letterSpacing: "0.08em", textAlign: "center" }}>
          Enter a URL above and click{" "}
          <span style={{ color: "var(--neon-cyan)" }}>SEND</span>
          <br />to see the response here
        </p>
      </div>
    );
  }

  const displayBody = prettyJson(response.body);
  const headerEntries = Object.entries(response.headers ?? {});

  return (
    <div className="glass-panel flex flex-col overflow-hidden h-full">
      {/* Status bar */}
      <div
        className="flex items-center gap-4 px-4 py-2.5 shrink-0"
        style={{ borderBottom: "1px solid var(--border-subtle)" }}
      >
        {response.error ? (
          <span style={{ color: "var(--danger)", fontFamily: "monospace", fontSize: "12px" }}>
            ⚠ {response.error}
          </span>
        ) : (
          <>
            <span
              className="font-mono font-bold text-sm tabular-nums"
              style={{ color: statusColor(response.status) }}
            >
              {response.status}
            </span>
            <span style={{ color: "var(--text-muted)", fontSize: "11px" }}>
              {response.statusText}
            </span>
            <span
              className="font-mono text-[11px] tabular-nums ml-auto"
              style={{ color: durationColor(response.durationMs) }}
            >
              {response.durationMs} ms
            </span>
            {/* Content-Length if present */}
            {response.headers?.["content-length"] && (
              <span style={{ color: "var(--text-dim)", fontSize: "10px" }}>
                {Number(response.headers["content-length"]).toLocaleString()} B
              </span>
            )}
          </>
        )}
      </div>

      {/* Tabs */}
      <div
        className="flex items-center shrink-0"
        style={{ borderBottom: "1px solid var(--border-subtle)" }}
      >
        <button style={tabStyle(tab === "body")} onClick={() => setTab("body")}>
          Body
        </button>
        <button style={tabStyle(tab === "headers")} onClick={() => setTab("headers")}>
          Headers
          {headerEntries.length > 0 && (
            <span style={{ color: "var(--text-dim)", marginLeft: 4 }}>
              ({headerEntries.length})
            </span>
          )}
        </button>
      </div>

      {/* Body */}
      {tab === "body" && (
        <div className="flex-1 overflow-auto p-3">
          {response.body ? (
            <pre
              style={{
                fontFamily: "var(--font-mono, monospace)",
                fontSize:   "11px",
                lineHeight: 1.65,
                color:      "var(--text-primary)",
                whiteSpace: "pre-wrap",
                wordBreak:  "break-all",
                margin:     0,
              }}
            >
              {displayBody}
            </pre>
          ) : (
            <p style={{ color: "var(--text-dim)", fontSize: "11px" }}>Empty body</p>
          )}
        </div>
      )}

      {/* Headers */}
      {tab === "headers" && (
        <div className="flex-1 overflow-auto p-3">
          {headerEntries.length === 0 ? (
            <p style={{ color: "var(--text-dim)", fontSize: "11px" }}>No headers</p>
          ) : (
            <table style={{ width: "100%", borderCollapse: "collapse", fontSize: "11px" }}>
              <tbody>
                {headerEntries.map(([k, v]) => (
                  <tr key={k} style={{ borderBottom: "1px solid var(--border-subtle)" }}>
                    <td
                      style={{
                        padding:    "4px 8px 4px 0",
                        color:      "var(--neon-cyan)",
                        fontFamily: "var(--font-mono, monospace)",
                        verticalAlign: "top",
                        whiteSpace: "nowrap",
                        width:      "45%",
                      }}
                    >
                      {k}
                    </td>
                    <td
                      style={{
                        padding:    "4px 0",
                        color:      "var(--text-muted)",
                        fontFamily: "var(--font-mono, monospace)",
                        wordBreak:  "break-all",
                      }}
                    >
                      {v}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </div>
  );
}
