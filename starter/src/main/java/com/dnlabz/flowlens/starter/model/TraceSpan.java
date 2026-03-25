package com.dnlabz.flowlens.starter.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * One instrumented method call in a trace tree.
 *
 * <p>Children are nested calls made by this method — building a full tree
 * rather than a flat list makes sequence-diagram generation trivial without
 * needing to re-discover caller/callee relationships on the frontend.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TraceSpan {

    private final String  className;
    private final String  methodName;
    private final long    durationMs;
    private final boolean error;
    private final List<TraceSpan> children = new ArrayList<>();

    public TraceSpan(String className, String methodName, long durationMs, boolean error) {
        this.className  = className;
        this.methodName = methodName;
        this.durationMs = durationMs;
        this.error      = error;
    }

    public void addChild(TraceSpan child) {
        children.add(child);
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    public String getClassName()  { return className;  }
    public String getMethodName() { return methodName; }
    public long   getDurationMs() { return durationMs; }
    public boolean isError()      { return error;      }
    public List<TraceSpan> getChildren() { return children; }
}
