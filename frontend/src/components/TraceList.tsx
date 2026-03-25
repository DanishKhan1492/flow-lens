"use client";

import { useState } from "react";
import type { DiscoveredEndpoint, EntryPointType } from "@/types/trace";

interface Props {
  endpoints:          DiscoveredEndpoint[];
  selectedEndpointId: string | null;
  onSelect:           (ep: DiscoveredEndpoint) => void;
}

const TABS: { label: string; value: EntryPointType | "ALL" }[] = [
  { label: "All",       value: "ALL"       },
  { label: "API",       value: "API"       },
  { label: "Consumer",  value: "CONSUMER"  },
  { label: "Scheduler", value: "SCHEDULER" },
];

const TYPE_COLOR: Record<EntryPointType, string> = {
  API:       "var(--neon-cyan)",
  CONSUMER:  "#b97cff",
  SCHEDULER: "var(--warn-orange)",
};

const TYPE_BADGE: Record<EntryPointType, string> = {
  API:       "API",
  CONSUMER:  "CON",
  SCHEDULER: "SCH",
};

export default function TraceList({ endpoints, selectedEndpointId, onSelect }: Props) {
  const [activeTab,      setActiveTab]      = useState<EntryPointType | "ALL">("ALL");
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set());

  const filtered = activeTab === "ALL"
    ? endpoints
    : endpoints.filter(e => e.type === activeTab);

  // Build ordered group map
  const groupOrder: string[] = [];
  const groupMap = new Map<string, DiscoveredEndpoint[]>();
  for (const ep of filtered) {
    if (!groupMap.has(ep.group)) {
      groupOrder.push(ep.group);
      groupMap.set(ep.group, []);
    }
    groupMap.get(ep.group)!.push(ep);
  }

  const countByType = (type: EntryPointType) => endpoints.filter(e => e.type === type).length;

  const toggleGroup = (group: string) =>
    setExpandedGroups(prev => {
      const next = new Set(prev);
      if (next.has(group)) next.delete(group); else next.add(group);
      return next;
    });

  return (
    <div className="glass-panel flex flex-col overflow-hidden h-full">
      {/* Header */}
      <div
        className="flex items-center justify-between px-4 py-3 shrink-0"
        style={{ borderBottom: "1px solid var(--border-subtle)" }}
      >
        <span className="font-display text-[10px] tracking-[0.2em] uppercase"
              style={{ color: "var(--neon-cyan)" }}>
          Endpoints
          {endpoints.length > 0 && (
            <span className="ml-2 font-mono" style={{ color: "var(--text-dim)" }}>
              ({endpoints.length})
            </span>
          )}
        </span>
      </div>

      {/* Category filter tabs */}
      <div
        className="flex shrink-0 px-2 pt-2 gap-1"
        style={{ borderBottom: "1px solid var(--border-subtle)" }}
      >
        {TABS.map(tab => {
          const count  = tab.value === "ALL" ? endpoints.length : countByType(tab.value as EntryPointType);
          const active = activeTab === tab.value;
          return (
            <button
              key={tab.value}
              onClick={() => setActiveTab(tab.value)}
              className="font-display text-[9px] tracking-widest px-2 py-1 rounded-t transition-all"
              style={{
                background:   active ? "rgba(0,243,255,0.08)" : "transparent",
                border:       active ? "1px solid rgba(0,243,255,0.3)" : "1px solid transparent",
                borderBottom: "1px solid transparent",
                color:        active ? "var(--neon-cyan)" : "var(--text-muted)",
                cursor:       "pointer",
              }}
            >
              {tab.label}{count > 0 && <span className="ml-1 opacity-60">{count}</span>}
            </button>
          );
        })}
      </div>

      {/* Grouped endpoint rows */}
      <div className="flex-1 overflow-y-auto">
        {filtered.length === 0 ? (
          <div className="flex items-center justify-center h-32"
               style={{ color: "var(--text-dim)", fontSize: "11px" }}>
            {endpoints.length === 0 ? "Discovering endpoints…" : "No endpoints in this category"}
          </div>
        ) : (
          groupOrder.map(group => {
            const items       = groupMap.get(group)!;
            const collapsed   = !expandedGroups.has(group);
            const hasSelected = items.some(ep => ep.id === selectedEndpointId);

            return (
              <div key={group}>
                {/* Group header */}
                <button
                  onClick={() => toggleGroup(group)}
                  className="w-full flex items-center justify-between px-3 py-2 transition-colors"
                  style={{
                    borderBottom: "1px solid var(--border-subtle)",
                    background:   hasSelected ? "rgba(0,243,255,0.04)" : "rgba(0,0,0,0.15)",
                    cursor:       "pointer",
                  }}
                >
                  <div className="flex items-center gap-2 min-w-0">
                    <span
                      className="font-mono text-[9px] shrink-0 transition-transform"
                      style={{
                        color:       "var(--text-dim)",
                        display:     "inline-block",
                        transform:   collapsed ? "rotate(-90deg)" : "rotate(0deg)",
                      }}
                    >
                      ▾
                    </span>
                    <span
                      className="font-mono text-[10px] truncate font-semibold"
                      style={{ color: hasSelected ? "var(--neon-cyan)" : "var(--text-muted)" }}
                    >
                      {group}
                    </span>
                  </div>
                  <span
                    className="font-mono text-[9px] px-1.5 py-0.5 rounded shrink-0"
                    style={{
                      color:      "var(--text-dim)",
                      background: "rgba(255,255,255,0.04)",
                      border:     "1px solid var(--border-subtle)",
                    }}
                  >
                    {items.length}
                  </span>
                </button>

                {/* Endpoint rows */}
                {!collapsed && items.map(ep => {
                  const isSelected = ep.id === selectedEndpointId;
                  const typeColor  = TYPE_COLOR[ep.type];

                  return (
                    <button
                      key={ep.id}
                      onClick={() => onSelect(ep)}
                      className="w-full text-left pl-8 pr-4 py-2.5 transition-colors"
                      style={{
                        borderBottom: "1px solid var(--border-subtle)",
                        borderLeft:   isSelected ? "3px solid var(--neon-cyan)" : "3px solid transparent",
                        background:   isSelected ? "rgba(0,243,255,0.06)" : "transparent",
                        cursor:       "pointer",
                      }}
                    >
                      <div className="flex items-center gap-1.5 min-w-0">
                        <span
                          className="font-mono text-[8px] px-1 rounded shrink-0"
                          style={{ color: typeColor, border: `1px solid ${typeColor}`, opacity: 0.8 }}
                        >
                          {TYPE_BADGE[ep.type]}
                        </span>
                        <span
                          className="font-mono text-[11px] truncate flex-1"
                          style={{ color: isSelected ? "var(--text-primary)" : "var(--text-muted)" }}
                        >
                          {ep.label}
                        </span>
                      </div>
                      <div className="mt-0.5 font-mono text-[9px]"
                           style={{ color: "var(--text-dim)" }}>
                        {ep.className}.{ep.methodName}()
                      </div>
                    </button>
                  );
                })}
              </div>
            );
          })
        )}
      </div>
    </div>
  );
}
