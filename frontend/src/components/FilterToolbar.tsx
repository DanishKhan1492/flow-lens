"use client";

interface Props {
  traceFilter:    string;
  methodFilter:   string;
  onTraceFilter:  (v: string) => void;
  onMethodFilter: (v: string) => void;
}

export default function FilterToolbar({
  traceFilter,
  methodFilter,
  onTraceFilter,
  onMethodFilter,
}: Props) {
  return (
    <div className="glass-panel p-4 flex flex-col gap-3">
      {/* Panel header */}
      <p
        className="font-display text-[10px] tracking-[0.2em] uppercase"
        style={{ color: "var(--neon-cyan)" }}
      >
        Filter
      </p>

      {/* Trace ID filter */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="filter-trace"
          className="text-[10px] tracking-widest uppercase"
          style={{ color: "var(--text-muted)" }}
        >
          Trace ID
        </label>
        <div className="relative">
          <span
            className="absolute left-2 top-1/2 -translate-y-1/2 text-[10px] select-none pointer-events-none"
            style={{ color: "var(--text-dim)" }}
          >
            #
          </span>
          <input
            id="filter-trace"
            type="text"
            value={traceFilter}
            onChange={(e) => onTraceFilter(e.target.value)}
            placeholder="a1b2c3d4…"
            className="neon-input pl-5"
            spellCheck={false}
            autoComplete="off"
          />
          {traceFilter && (
            <button
              onClick={() => onTraceFilter("")}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] transition-colors"
              style={{ color: "var(--text-dim)" }}
              aria-label="Clear trace filter"
            >
              ✕
            </button>
          )}
        </div>
      </div>

      {/* Method filter */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="filter-method"
          className="text-[10px] tracking-widest uppercase"
          style={{ color: "var(--text-muted)" }}
        >
          Method Name
        </label>
        <div className="relative">
          <span
            className="absolute left-2 top-1/2 -translate-y-1/2 text-[10px] select-none pointer-events-none"
            style={{ color: "var(--text-dim)" }}
          >
            ƒ
          </span>
          <input
            id="filter-method"
            type="text"
            value={methodFilter}
            onChange={(e) => onMethodFilter(e.target.value)}
            placeholder="handleRequest…"
            className="neon-input pl-5"
            spellCheck={false}
            autoComplete="off"
          />
          {methodFilter && (
            <button
              onClick={() => onMethodFilter("")}
              className="absolute right-2 top-1/2 -translate-y-1/2 text-[10px] transition-colors"
              style={{ color: "var(--text-dim)" }}
              aria-label="Clear method filter"
            >
              ✕
            </button>
          )}
        </div>
      </div>

      {/* Active filter badge */}
      {(traceFilter || methodFilter) && (
        <button
          onClick={() => { onTraceFilter(""); onMethodFilter(""); }}
          className="text-[10px] tracking-widest uppercase mt-1 text-left transition-colors"
          style={{ color: "var(--warn-orange)" }}
        >
          ✕ Clear all filters
        </button>
      )}
    </div>
  );
}
