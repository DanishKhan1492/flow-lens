package com.dnlabz.flowlens.starter.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * One complete recorded interaction — an API call, a consumer invocation,
 * or a scheduler tick — captured as a tree of {@link TraceSpan}s.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceRecord {

    private final String         id;
    private final EntryPointType entryPointType;
    /** Human-readable label: "GET /api/users" for APIs, "ClassName.method" for others. */
    private final String         label;
    private final String         timestamp;
    private final long           durationMs;
    private final boolean        error;
    /** Root span — the entry-point method itself; children are nested calls. */
    private final TraceSpan      rootSpan;

    public TraceRecord(
            EntryPointType entryPointType,
            String label,
            long durationMs,
            boolean error,
            TraceSpan rootSpan) {
        this.id             = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.entryPointType = entryPointType;
        this.label          = label;
        this.timestamp      = Instant.now().toString();
        this.durationMs     = durationMs;
        this.error          = error;
        this.rootSpan       = rootSpan;
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String         getId()             { return id;             }
    public EntryPointType getEntryPointType() { return entryPointType; }
    public String         getLabel()          { return label;          }
    public String         getTimestamp()      { return timestamp;      }
    public long           getDurationMs()     { return durationMs;     }
    public boolean        isError()           { return error;          }
    public TraceSpan      getRootSpan()       { return rootSpan;       }
}
