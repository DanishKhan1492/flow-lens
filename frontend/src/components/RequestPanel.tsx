"use client";

import { useState } from "react";

export interface ReqHeader { id: number; key: string; value: string; }

interface Props {
  headers:          ReqHeader[];
  body:             string;
  onHeadersChange:  (h: ReqHeader[]) => void;
  onBodyChange:     (b: string) => void;
}

type Tab = "headers" | "body";

let nextId = 100;

function HeaderRow({
  row, onChange, onRemove,
}: {
  row: ReqHeader;
  onChange: (r: ReqHeader) => void;
  onRemove: () => void;
}) {
  const inputStyle: React.CSSProperties = {
    background:   "transparent",
    border:       "none",
    outline:      "none",
    color:        "var(--text-primary)",
    fontFamily:   "var(--font-mono, monospace)",
    fontSize:     "11px",
    width:        "100%",
    padding:      "4px 0",
  };
  return (
    <div
      className="flex items-center gap-2 group"
      style={{ borderBottom: "1px solid var(--border-subtle)", padding: "2px 0" }}
    >
      <input
        style={{ ...inputStyle, color: "var(--neon-cyan)", flex: "0 0 38%" }}
        placeholder="Header-Name"
        value={row.key}
        onChange={e => onChange({ ...row, key: e.target.value })}
      />
      <span style={{ color: "var(--border-subtle)" }}>:</span>
      <input
        style={{ ...inputStyle, flex: 1 }}
        placeholder="value"
        value={row.value}
        onChange={e => onChange({ ...row, value: e.target.value })}
      />
      <button
        onClick={onRemove}
        style={{
          color:      "var(--text-dim)",
          background: "none",
          border:     "none",
          cursor:     "pointer",
          fontSize:   "14px",
          lineHeight: 1,
          opacity:    0.5,
          padding:    "0 2px",
        }}
        title="Remove"
      >
        ×
      </button>
    </div>
  );
}

export default function RequestPanel({ headers, body, onHeadersChange, onBodyChange }: Props) {
  const [tab, setTab] = useState<Tab>("headers");

  const addHeader = () =>
    onHeadersChange([...headers, { id: ++nextId, key: "", value: "" }]);

  const updateHeader = (id: number, updated: ReqHeader) =>
    onHeadersChange(headers.map(h => (h.id === id ? updated : h)));

  const removeHeader = (id: number) =>
    onHeadersChange(headers.filter(h => h.id !== id));

  const tabStyle = (active: boolean): React.CSSProperties => ({
    fontFamily:      "var(--font-display, sans-serif)",
    fontSize:        "10px",
    letterSpacing:   "0.15em",
    textTransform:   "uppercase",
    padding:         "4px 10px",
    background:      "none",
    border:          "none",
    borderBottom:    active ? "2px solid var(--neon-cyan)" : "2px solid transparent",
    color:           active ? "var(--neon-cyan)" : "var(--text-dim)",
    cursor:          "pointer",
    transition:      "color 0.15s, border-color 0.15s",
  });

  return (
    <div className="glass-panel flex flex-col overflow-hidden h-full">
      {/* Tab bar */}
      <div
        className="flex items-center shrink-0"
        style={{ borderBottom: "1px solid var(--border-subtle)" }}
      >
        <button style={tabStyle(tab === "headers")} onClick={() => setTab("headers")}>
          Headers
          {headers.filter(h => h.key.trim()).length > 0 && (
            <span style={{ color: "var(--text-dim)", marginLeft: 4 }}>
              ({headers.filter(h => h.key.trim()).length})
            </span>
          )}
        </button>
        <button style={tabStyle(tab === "body")} onClick={() => setTab("body")}>
          Body
        </button>
      </div>

      {/* Headers tab */}
      {tab === "headers" && (
        <div className="flex flex-col overflow-hidden flex-1">
          <div className="flex-1 overflow-y-auto px-3 py-2">
            {headers.map(h => (
              <HeaderRow
                key={h.id}
                row={h}
                onChange={r => updateHeader(h.id, r)}
                onRemove={() => removeHeader(h.id)}
              />
            ))}
            {headers.length === 0 && (
              <p style={{ color: "var(--text-dim)", fontSize: "11px", padding: "8px 0" }}>
                No headers yet
              </p>
            )}
          </div>
          <div
            className="shrink-0 px-3 py-2"
            style={{ borderTop: "1px solid var(--border-subtle)" }}
          >
            <button
              onClick={addHeader}
              style={{
                fontFamily:    "var(--font-display, sans-serif)",
                fontSize:      "10px",
                letterSpacing: "0.15em",
                textTransform: "uppercase",
                color:         "var(--neon-cyan)",
                background:    "rgba(0,243,255,0.06)",
                border:        "1px solid rgba(0,243,255,0.25)",
                borderRadius:  "4px",
                padding:       "3px 10px",
                cursor:        "pointer",
              }}
            >
              + Add Header
            </button>
          </div>
        </div>
      )}

      {/* Body tab */}
      {tab === "body" && (
        <div className="flex-1 overflow-hidden flex flex-col p-2">
          <textarea
            value={body}
            onChange={e => onBodyChange(e.target.value)}
            placeholder='{"key": "value"}'
            spellCheck={false}
            style={{
              flex:        1,
              resize:      "none",
              background:  "transparent",
              border:      "1px solid var(--border-subtle)",
              borderRadius:"4px",
              padding:     "8px",
              color:       "var(--text-primary)",
              fontFamily:  "var(--font-mono, monospace)",
              fontSize:    "11px",
              lineHeight:  1.6,
              outline:     "none",
            }}
          />
        </div>
      )}
    </div>
  );
}
