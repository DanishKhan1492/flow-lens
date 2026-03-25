"use client";

import { useState, useEffect, useCallback, useRef } from "react";
import dynamic from "next/dynamic";
import { motion } from "motion/react";
import { useTraceWebSocket } from "@/hooks/useTraceWebSocket";
import TraceList  from "@/components/TraceList";
import type { DiscoveredEndpoint } from "@/types/trace";

// MermaidDiagram must be client-only — mermaid requires DOM APIs
const MermaidDiagram = dynamic(
  () => import("@/components/MermaidDiagram"),
  { ssr: false, loading: () => (
    <div className="flex items-center justify-center h-full"
         style={{ color: "var(--text-dim)", fontSize: "11px" }}>
      Loading diagram engine…
    </div>
  )}
);

// ── Framer Motion variants ────────────────────────────────────────────────────
const containerVariants = {
  hidden:  { opacity: 0 },
  visible: {
    opacity: 1,
    transition: { staggerChildren: 0.08, delayChildren: 0.15 },
  },
};

const panelVariants = {
  hidden:  { opacity: 0, y: 14 },
  visible: {
    opacity: 1,
    y: 0,
    transition: { duration: 0.38, ease: [0.25, 0.46, 0.45, 0.94] as [number,number,number,number] },
  },
};

// ── Constants ───────────────────────────────────────────────────────────────

/**
 * Returns everything in the current pathname BEFORE "/flow-lens".
 * e.g. "/uz-certificate-manager/flow-lens/" → "/uz-certificate-manager"
 *      "/flow-lens/"                        → ""
 * This lets every fetch and WS URL work regardless of Spring's context-path.
 */
function getBasePath(): string {
  if (typeof window === "undefined") return "";
  return window.location.pathname.replace(/\/flow-lens(\/.*)?$/, "");
}

function getWsUrl(): string {
  if (typeof window === "undefined") return "";
  const proto = window.location.protocol === "https:" ? "wss:" : "ws:";
  return `${proto}//${window.location.host}${getBasePath()}/flow-lens/ws`;
}

// ── Helpers ──────────────────────────────────────────────────────────────────

// ── Component ────────────────────────────────────────────────────────────────
export default function DashboardPage() {
  const { connectionState } = useTraceWebSocket(getWsUrl());
  const [endpoints,         setEndpoints]         = useState<DiscoveredEndpoint[]>([]);
  const [selectedEndpoint,  setSelectedEndpoint]  = useState<DiscoveredEndpoint | null>(null);
  const [staticDiagramCode, setStaticDiagramCode] = useState<string | null>(null);
  const [loadingDiagram,    setLoadingDiagram]    = useState(false);
  const [sidebarOpen,       setSidebarOpen]       = useState(true);
  const [sidebarWidth,      setSidebarWidth]      = useState(320);
  const resizeState = useRef({ active: false, startX: 0, startW: 0 });

  const onResizeDown = (e: React.MouseEvent) => {
    e.preventDefault();
    resizeState.current = { active: true, startX: e.clientX, startW: sidebarWidth };
    const onMove = (ev: MouseEvent) => {
      if (!resizeState.current.active) return;
      const w = resizeState.current.startW + (ev.clientX - resizeState.current.startX);
      setSidebarWidth(Math.max(200, Math.min(600, w)));
    };
    const onUp = () => {
      resizeState.current.active = false;
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup",  onUp);
    };
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup",   onUp);
  };

  // Fetch discovered endpoints on mount
  useEffect(() => {
    const base = getBasePath();
    fetch(`${base}/flow-lens/api/endpoints`)
      .then(r => r.json())
      .then((data: DiscoveredEndpoint[]) => setEndpoints(data))
      .catch(() => {});
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // When user clicks an endpoint, fetch its static (code-analysis) diagram
  const handleEndpointSelect = useCallback(async (ep: DiscoveredEndpoint) => {
    setSelectedEndpoint(ep);
    setStaticDiagramCode(null);
    setLoadingDiagram(true);
    try {
      const base = getBasePath();
      const res  = await fetch(`${base}/flow-lens/api/diagram?id=${encodeURIComponent(ep.id)}`);
      const data = await res.json() as { diagram: string };
      setStaticDiagramCode(data.diagram);
    } catch {
      setStaticDiagramCode(null);
    } finally {
      setLoadingDiagram(false);
    }
  }, []);

  // ── JSX ────────────────────────────────────────────────────────────────────
  return (
    <div
      className="relative flex flex-col h-screen overflow-hidden"
      style={{ zIndex: 1 }}
    >
      {/* ── Top bar ─────────────────────────────────────────────────────── */}
      <header
        className="relative flex items-center justify-between px-5 py-3 shrink-0 overflow-hidden"
        style={{
          borderBottom:   "1px solid var(--border-subtle)",
          background:     "rgba(13,17,23,0.85)",
          backdropFilter: "blur(12px)",
          zIndex:         10,
        }}
      >
        <div
          className="pointer-events-none absolute inset-y-0 w-16 animate-[scan-h_5s_linear_infinite]"
          style={{ background: "linear-gradient(90deg, transparent, rgba(0,243,255,0.06), transparent)" }}
        />
        {/* Logo */}
        <div className="flex items-center gap-3">
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
            <circle cx="9" cy="9" r="7" stroke="#00F3FF" strokeWidth="1.5" strokeDasharray="2 2" />
            <circle cx="9" cy="9" r="3" fill="#00F3FF" fillOpacity="0.6" />
          </svg>
          <span className="font-display text-base tracking-[0.18em] select-none" style={{ color: "var(--neon-cyan)" }}>
            FLOW LENS
          </span>
          <span className="font-mono text-[10px] px-1.5 py-0.5 rounded"
            style={{ color: "var(--text-dim)", border: "1px solid var(--border-subtle)" }}>
            v0.1.0
          </span>
        </div>
        {/* Connection status */}
        <div className="flex items-center gap-5">
          <span className="font-mono text-[11px] tabular-nums" style={{ color: "var(--text-muted)" }}>
            {endpoints.length} endpoints
          </span>
          <div className="flex items-center gap-1.5">
            <span className={`inline-block w-2 h-2 rounded-full ${
              connectionState === "connected"  ? "dot-connected"  :
              connectionState === "connecting" ? "dot-connecting" : "dot-disconnected"
            }`} />
            <span className="font-display text-[10px] tracking-widest" style={{
              color: connectionState === "connected"  ? "var(--neon-cyan)"    :
                     connectionState === "connecting" ? "var(--warn-orange)" : "var(--text-dim)",
            }}>
              {connectionState === "connected" ? "LIVE" : connectionState === "connecting" ? "CONNECTING…" : "OFFLINE"}
            </span>
          </div>
        </div>
      </header>

      {/* ── Main content ──────────────────────────────────────────────── */}
      <motion.div
        variants={containerVariants}
        initial="hidden"
        animate="visible"
        className="flex-1 overflow-hidden flex p-3 gap-3"
      >
        {/* ── LEFT: Endpoint list ───────────────────────────────────────── */}
        {sidebarOpen && (
          <motion.div
            variants={panelVariants}
            className="overflow-hidden shrink-0"
            style={{ width: sidebarWidth }}
          >
            <TraceList
              endpoints={endpoints}
              selectedEndpointId={selectedEndpoint?.id ?? null}
              onSelect={handleEndpointSelect}
            />
          </motion.div>
        )}

        {/* ── Resize handle ─────────────────────────────────────────────── */}
        {sidebarOpen && (
          <div
            className="shrink-0 w-1.5 rounded-full transition-colors hover:bg-[rgba(0,243,255,0.35)] active:bg-[rgba(0,243,255,0.5)] cursor-col-resize"
            style={{ background: "rgba(0,243,255,0.12)" }}
            onMouseDown={onResizeDown}
            title="Drag to resize"
          />
        )}

        {/* ── CENTER: Sequence diagram ──────────────────────────────────── */}
        <motion.div
          variants={panelVariants}
          className="glass-panel p-4 overflow-hidden flex flex-col flex-1 min-w-0"
        >
          <MermaidDiagram
            staticCode={staticDiagramCode}
            loading={loadingDiagram}
            label={selectedEndpoint
              ? `${selectedEndpoint.type} · ${selectedEndpoint.label}`
              : undefined}
            sidebarOpen={sidebarOpen}
            onToggleSidebar={() => setSidebarOpen(s => !s)}
          />
        </motion.div>
      </motion.div>
    </div>
  );
}
