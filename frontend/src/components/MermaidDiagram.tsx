"use client";

import { useEffect, useRef, useCallback, useState, useMemo } from "react";

interface Props {
  staticCode?:      string | null;
  label?:           string;
  loading?:         boolean;
  sidebarOpen?:     boolean;
  onToggleSidebar?: () => void;
}

// ── Step parser (ported from mermaid-code-to-gif) ────────────────────────────

function parseMermaidSteps(code: string): string[] {
  const lines = code.split("\n");
  const first = lines.find(l => l.trim().length > 0)?.trim() ?? "";

  if (first.startsWith("sequenceDiagram")) {
    const hi = lines.findIndex(l => l.trim().startsWith("sequenceDiagram"));
    if (hi === -1) return [code];
    const header = lines[hi];
    const decls: string[] = [];
    const actions: string[] = [];
    for (let i = hi + 1; i < lines.length; i++) {
      const t = lines[i].trim();
      if (!t || t.startsWith("%%")) continue;
      if (t.startsWith("participant") || t.startsWith("actor")) decls.push(lines[i]);
      else actions.push(lines[i]);
    }
    const declBlock = decls.join("\n");
    const steps: string[] = [header];
    if (decls.length > 0) steps.push(header + "\n" + declBlock);
    for (let i = 0; i < actions.length; i++) {
      steps.push(header + "\n" + declBlock + "\n" + actions.slice(0, i + 1).join("\n"));
    }
    return steps;
  }
  return [code];
}

// ── Component ─────────────────────────────────────────────────────────────────────────────────

export default function MermaidDiagram({ staticCode, label, loading, sidebarOpen, onToggleSidebar }: Props) {
  const containerRef    = useRef<HTMLDivElement>(null);
  const scrollRef       = useRef<HTMLDivElement>(null);
  const mermaidRef      = useRef<typeof import("mermaid")["default"] | null>(null);
  const renderIdRef     = useRef(0);
  const naturalSizeRef  = useRef<{ w: number; h: number } | null>(null);
  const panRef          = useRef<{ x: number; y: number; sl: number; st: number } | null>(null);
  const playTimerRef    = useRef<ReturnType<typeof setInterval> | null>(null);

  const [zoom,        setZoom]        = useState(1);
  const [isPanning,   setIsPanning]   = useState(false);
  const [copied,      setCopied]      = useState(false);
  // Step-flow state
  const [stepMode,    setStepMode]    = useState(false);
  const [stepSvgs,    setStepSvgs]    = useState<string[]>([]);
  const [stepIndex,   setStepIndex]   = useState(0);
  const [prerendering,setPrerendering]= useState(false);
  const [playing,     setPlaying]     = useState(false);
  const [speed,       setSpeed]       = useState<0.5 | 1 | 2>(1);

  const zoomRef = useRef(zoom);
  useEffect(() => { zoomRef.current = zoom; }, [zoom]);

  // ── Derived step data ──────────────────────────────────────────────────────
  const definition = staticCode ?? null;
  const steps = useMemo(() => definition ? parseMermaidSteps(definition) : [], [definition]);

  // ── Mermaid initialiser helper (shared between normal + step renders) ─────────
  const ensureMermaid = useCallback(async () => {
    if (mermaidRef.current) return mermaidRef.current;
    const { default: mermaid } = await import("mermaid");
    mermaid.initialize({
      startOnLoad: false,
      maxTextSize:  500000,
      maxEdges:     500,
      theme: "dark",
      themeVariables: {
        lineColor:             "#00F3FF",
        primaryColor:          "#1c2128",
        primaryTextColor:      "#E6EDF3",
        primaryBorderColor:    "rgba(0,243,255,0.28)",
        actorBkg:              "#161B22",
        actorBorder:           "rgba(0,243,255,0.28)",
        actorTextColor:        "#E6EDF3",
        signalColor:           "#00F3FF",
        signalTextColor:       "#E6EDF3",
        noteBkgColor:          "#1c2128",
        noteTextColor:         "#8B949E",
        activationBkgColor:    "rgba(0,243,255,0.08)",
        activationBorderColor: "#00F3FF",
        fontSize:              "12px",
      },
      sequence: {
        useMaxWidth:         true,
        showSequenceNumbers: true,
        boxTextMargin:       4,
        noteMargin:          8,
      },
    });
    mermaidRef.current = mermaid;
    return mermaid;
  }, []);

  // ── SVG display helper (sets dimensions + writes to DOM) ──────────────────
  const displaySvg = useCallback((svg: string) => {
    if (!containerRef.current) return;
    containerRef.current.innerHTML = svg;
    const svgEl = containerRef.current.querySelector<SVGSVGElement>("svg");
    if (svgEl) {
      svgEl.style.maxWidth = "none";
      const vb = svgEl.viewBox?.baseVal;
      const nw = vb && vb.width  > 0 ? vb.width  : (svgEl.width?.baseVal?.value  ?? 800);
      const nh = vb && vb.height > 0 ? vb.height : (svgEl.height?.baseVal?.value ?? 600);
      naturalSizeRef.current = { w: nw, h: nh };
      const z = zoomRef.current;
      svgEl.setAttribute("width",  String(Math.round(nw * z)));
      svgEl.setAttribute("height", String(Math.round(nh * z)));
    }
  }, []);

  // ── Normal (non-step) render ──────────────────────────────────────────────
  const render = useCallback(async () => {
    const container = containerRef.current;
    if (!container) return;
    if (stepMode) return; // step mode manages its own display

    if (loading) {
      container.innerHTML = `<p style="color:var(--text-dim);font-size:11px;padding:2rem 1rem;text-align:center;">Analysing code…</p>`;
      return;
    }
    if (!definition) {
      container.innerHTML = `<p style="color:var(--text-dim);font-size:11px;padding:2rem 1rem;text-align:center;">Select an endpoint to see its sequence diagram.</p>`;
      return;
    }

    const mermaid    = await ensureMermaid();
    const thisRender = ++renderIdRef.current;
    const diagramId  = `tc-seq-${Date.now()}`;
    try {
      const { svg, bindFunctions } = await mermaid.render(diagramId, definition);
      if (thisRender !== renderIdRef.current || !containerRef.current) return;
      displaySvg(svg);
      if (bindFunctions) bindFunctions(containerRef.current);
    } catch {
      if (thisRender !== renderIdRef.current) return;
      if (containerRef.current) {
        const escaped = (definition ?? "").replace(/</g, "&lt;").replace(/>/g, "&gt;");
        containerRef.current.innerHTML =
          `<div style="padding:1rem"><p style="color:var(--text-dim);font-size:11px;margin-bottom:0.75rem;text-align:center;">Diagram too complex to render — raw Mermaid code:</p><pre style="font-size:10px;color:#8B949E;overflow:auto;white-space:pre-wrap;background:rgba(0,0,0,0.3);padding:0.75rem;border-radius:6px;border:1px solid var(--border-subtle);">${escaped}</pre></div>`;
      }
    }
  }, [definition, loading, stepMode, ensureMermaid, displaySvg]);

  useEffect(() => { void render(); }, [render]);

  // ── Pre-render all step SVGs when step-mode is toggled on ─────────────────
  useEffect(() => {
    if (!stepMode || steps.length === 0) {
      setStepSvgs([]);
      setStepIndex(0);
      return;
    }
    let cancelled = false;
    setPrerendering(true);
    setStepIndex(0);

    (async () => {
      const mermaid = await ensureMermaid();
      const raw: string[] = [];
      for (let i = 0; i < steps.length; i++) {
        if (cancelled) return;
        const id = `tc-step-${i}-${Date.now()}`;
        try {
          const { svg } = await mermaid.render(id, steps[i]);
          raw.push(svg);
        } catch {
          raw.push(raw.length > 0 ? raw[raw.length - 1] : "");
        }
      }
      if (cancelled) return;
      // Deduplicate consecutive identical frames
      const norm = (s: string) => s.replace(/id="[^"]*"/g, "").replace(/mermaid-\d+/g, "");
      const deduped: string[] = [];
      for (const s of raw) {
        if (!deduped.length || norm(s) !== norm(deduped[deduped.length - 1])) deduped.push(s);
      }
      setStepSvgs(deduped);
      setPrerendering(false);
    })();

    return () => { cancelled = true; };
  }, [stepMode, steps, ensureMermaid]);

  // ── Display current step frame ────────────────────────────────────────────
  useEffect(() => {
    if (!stepMode || !stepSvgs.length) return;
    const svg = stepSvgs[stepIndex] ?? stepSvgs[stepSvgs.length - 1];
    if (svg) displaySvg(svg);
  }, [stepMode, stepSvgs, stepIndex, displaySvg]);

  // ── Auto-play timer ───────────────────────────────────────────────────────
  useEffect(() => {
    if (playTimerRef.current) clearInterval(playTimerRef.current);
    if (!playing || !stepMode || !stepSvgs.length) return;
    playTimerRef.current = setInterval(() => {
      setStepIndex(i => {
        if (i >= stepSvgs.length - 1) { setPlaying(false); return i; }
        return i + 1;
      });
    }, 1200 / speed);
    return () => { if (playTimerRef.current) clearInterval(playTimerRef.current); };
  }, [playing, stepMode, stepSvgs.length, speed]);

  // Stop playing when mode turns off
  useEffect(() => { if (!stepMode) setPlaying(false); }, [stepMode]);

  // ── Keyboard navigation in step mode ─────────────────────────────────────
  useEffect(() => {
    if (!stepMode) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "ArrowRight" || e.key === "ArrowDown") {
        setPlaying(false);
        setStepIndex(i => Math.min(i + 1, stepSvgs.length - 1));
      } else if (e.key === "ArrowLeft" || e.key === "ArrowUp") {
        setPlaying(false);
        setStepIndex(i => Math.max(i - 1, 0));
      } else if (e.key === " ") {
        e.preventDefault();
        setPlaying(p => !p);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [stepMode, stepSvgs.length]);

  // Apply zoom by scaling SVG width/height attributes directly.
  useEffect(() => {
    const svgEl = containerRef.current?.querySelector<SVGSVGElement>("svg");
    const ns    = naturalSizeRef.current;
    if (!svgEl || !ns) return;
    svgEl.setAttribute("width",  String(Math.round(ns.w * zoom)));
    svgEl.setAttribute("height", String(Math.round(ns.h * zoom)));
  }, [zoom]);

  // ── Pan handlers ──────────────────────────────────────────────────────────
  const handlePanStart = (e: React.MouseEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    const el = scrollRef.current;
    if (!el) return;
    panRef.current = { x: e.clientX, y: e.clientY, sl: el.scrollLeft, st: el.scrollTop };
    setIsPanning(true);
  };
  const handlePanMove = (e: React.MouseEvent<HTMLDivElement>) => {
    if (!panRef.current) return;
    const el = scrollRef.current!;
    el.scrollLeft = panRef.current.sl - (e.clientX - panRef.current.x);
    el.scrollTop  = panRef.current.st - (e.clientY - panRef.current.y);
  };
  const handlePanEnd = () => { panRef.current = null; setIsPanning(false); };

  // ── Copy handler ──────────────────────────────────────────────────────────
  const handleCopy = async () => {
    const code = definition;
    if (!code) return;
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard blocked */ }
  };

  const typeLabel = label ?? "select an endpoint";

  // ── Shared button styles ──────────────────────────────────────────────────
  const btnBase: React.CSSProperties = {
    background: "rgba(0,243,255,0.1)",
    color:      "var(--neon-cyan)",
    border:     "1px solid rgba(0,243,255,0.4)",
  };
  const btnDim: React.CSSProperties = {
    background: "rgba(0,243,255,0.05)",
    color:      "var(--text-dim)",
    border:     "1px solid var(--border-subtle)",
    opacity:    0.45,
    cursor:     "not-allowed",
  };

  const totalSteps = stepSvgs.length;

  return (
    <div className="flex flex-col h-full overflow-hidden">
      {/* ── Toolbar ──────────────────────────────────────────────────────── */}
      <div
        className="flex items-center justify-between px-1 pb-3 shrink-0"
        style={{ borderBottom: "1px solid var(--border-subtle)" }}
      >
        {/* Left: sidebar toggle + title */}
        <div className="flex items-center gap-2 shrink-0">
          {onToggleSidebar && (
            <button
              onClick={onToggleSidebar}
              className="flex items-center gap-1 px-2 h-7 rounded text-[10px] font-mono select-none transition-opacity hover:opacity-80"
              style={{ background: "rgba(0,243,255,0.08)", color: "var(--neon-cyan)", border: "1px solid rgba(0,243,255,0.3)" }}
              title={sidebarOpen ? "Hide endpoint list" : "Show endpoint list"}
            >
              {sidebarOpen ? "◀" : "▶"} Endpoints
            </button>
          )}
          <span className="font-display text-[10px] tracking-[0.2em] uppercase"
                style={{ color: "var(--neon-cyan)" }}>
            Sequence Diagram
          </span>
        </div>

        {/* Center: Step-flow toggle + Zoom */}
        <div className="flex items-center gap-3">
          {/* Step-flow toggle */}
          <button
            onClick={() => { setStepMode(m => !m); setPlaying(false); setStepIndex(0); }}
            className="h-9 px-4 flex items-center gap-2 rounded font-mono text-sm font-semibold select-none transition-all hover:opacity-90"
            style={stepMode
              ? { background: "rgba(0,243,255,0.22)", color: "var(--neon-cyan)", border: "1px solid rgba(0,243,255,0.7)" }
              : { background: "rgba(0,243,255,0.12)", color: "var(--neon-cyan)", border: "1px solid rgba(0,243,255,0.5)" }}
            title={stepMode ? "Exit step-flow mode" : "Enter step-flow mode (walk through diagram step by step)"}
          >
            {stepMode ? "▶ Step Flow ON" : "▶ Step Flow"}
          </button>

          {/* Zoom controls */}
          <div className="flex items-center gap-1.5">
            <button onClick={() => setZoom(z => Math.max(0.3, +(z - 0.15).toFixed(2)))}
              className="w-8 h-8 flex items-center justify-center rounded font-bold text-base select-none hover:opacity-80"
              style={btnBase} title="Zoom out">−</button>
            <button onClick={() => setZoom(1)}
              className="h-8 px-2 flex items-center justify-center rounded font-mono text-xs select-none hover:opacity-80"
              style={{ ...btnBase, minWidth: "3.5rem" }} title="Reset zoom">{Math.round(zoom * 100)}%</button>
            <button onClick={() => setZoom(z => Math.min(3, +(z + 0.15).toFixed(2)))}
              className="w-8 h-8 flex items-center justify-center rounded font-bold text-base select-none hover:opacity-80"
              style={btnBase} title="Zoom in">+</button>
          </div>
        </div>

        {/* Right: copy + label */}
        <div className="flex items-center gap-2 shrink-0">
          <button
            onClick={handleCopy}
            className="h-9 px-4 flex items-center gap-2 rounded font-mono text-sm font-semibold select-none hover:opacity-90 transition-all"
            style={{
              background: copied ? "rgba(0,243,255,0.22)" : "rgba(0,243,255,0.12)",
              color:      "var(--neon-cyan)",
              border:     `1px solid ${copied ? "rgba(0,243,255,0.7)" : "rgba(0,243,255,0.5)"}`,
            }}
            title="Copy Mermaid code"
          >{copied ? "✓ Copied" : "📋 Copy"}</button>
          <span className="font-mono text-[10px] truncate max-w-[20rem]"
                style={{ color: "var(--text-dim)" }}>{typeLabel}</span>
        </div>
      </div>

      {/* ── Step-flow controls bar ────────────────────────────────────────── */}
      {stepMode && (
        <div
          className="flex items-center gap-3 px-2 py-2 shrink-0 flex-wrap"
          style={{ borderBottom: "1px solid var(--border-subtle)", background: "rgba(0,243,255,0.03)" }}
        >
          {/* Prev */}
          <button
            onClick={() => { setPlaying(false); setStepIndex(i => Math.max(i - 1, 0)); }}
            disabled={stepIndex <= 0 || prerendering}
            className="flex items-center gap-1 px-3 h-8 rounded font-mono text-xs select-none transition-all"
            style={stepIndex <= 0 || prerendering ? btnDim : btnBase}
          >◀ Prev</button>

          {/* Counter */}
          <span className="font-mono text-xs tabular-nums px-2"
                style={{ color: "var(--neon-cyan)", minWidth: "5rem", textAlign: "center" }}>
            {prerendering
              ? "Loading…"
              : `Step ${stepIndex + 1} / ${totalSteps}`}
          </span>

          {/* Next */}
          <button
            onClick={() => { setPlaying(false); setStepIndex(i => Math.min(i + 1, totalSteps - 1)); }}
            disabled={stepIndex >= totalSteps - 1 || prerendering}
            className="flex items-center gap-1 px-3 h-8 rounded font-mono text-xs select-none transition-all"
            style={stepIndex >= totalSteps - 1 || prerendering ? btnDim : btnBase}
          >Next ▶</button>

          {/* Divider */}
          <div className="w-px h-5 shrink-0" style={{ background: "var(--border-subtle)" }} />

          {/* Play / Pause */}
          <button
            onClick={() => {
              if (stepIndex >= totalSteps - 1) setStepIndex(0);
              setPlaying(p => !p);
            }}
            disabled={prerendering || totalSteps === 0}
            className="flex items-center gap-1 px-3 h-8 rounded font-mono text-xs select-none transition-all"
            style={prerendering || !totalSteps ? btnDim
              : playing
                ? { background: "rgba(255,165,0,0.18)", color: "#FFA500", border: "1px solid rgba(255,165,0,0.5)" }
                : { background: "rgba(0,243,255,0.14)", color: "var(--neon-cyan)", border: "1px solid rgba(0,243,255,0.5)" }}
          >{playing ? "⏸ Pause" : "▶ Play"}</button>

          {/* Speed */}
          <div className="flex items-center gap-1">
            {([0.5, 1, 2] as const).map(s => (
              <button key={s}
                onClick={() => setSpeed(s)}
                className="px-2 h-7 rounded font-mono text-[11px] select-none transition-all"
                style={speed === s
                  ? { background: "rgba(0,243,255,0.22)", color: "var(--neon-cyan)", border: "1px solid rgba(0,243,255,0.6)" }
                  : { background: "rgba(0,243,255,0.05)", color: "var(--text-dim)",   border: "1px solid var(--border-subtle)" }}
              >{s}x</button>
            ))}
          </div>

          {/* Hint */}
          <span className="font-mono text-[10px] ml-auto" style={{ color: "var(--text-dim)", opacity: 0.6 }}>
            ← → or Space
          </span>
        </div>
      )}

      {/* ── Diagram output ────────────────────────────────────────────────── */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-auto pt-4"
        style={{ cursor: isPanning ? "grabbing" : "grab", userSelect: "none" }}
        onMouseDown={handlePanStart}
        onMouseMove={handlePanMove}
        onMouseUp={handlePanEnd}
        onMouseLeave={handlePanEnd}
      >
        <div ref={containerRef} className="mermaid-wrap" />
      </div>
    </div>
  );
}

