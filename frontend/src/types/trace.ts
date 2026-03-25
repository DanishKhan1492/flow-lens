/**
 * Category of the traced entry point.
 * Mirrors com.dnlabz.flowlens.starter.model.EntryPointType.
 */
export type EntryPointType = "API" | "CONSUMER" | "SCHEDULER";

/**
 * One instrumented method call in a trace tree.
 * Mirrors com.dnlabz.flowlens.starter.model.TraceSpan.
 */
export interface TraceSpan {
  className:  string;
  methodName: string;
  durationMs: number;
  error:      boolean;
  children:   TraceSpan[];
}

/**
 * One complete interaction — an API call, consumer invocation, or scheduler tick.
 * Mirrors com.dnlabz.flowlens.starter.model.TraceRecord.
 */
export interface TraceRecord {
  id:             string;
  entryPointType: EntryPointType;
  /** "GET /api/users" for APIs, "ClassName.method" for others. */
  label:          string;
  /** ISO-8601 UTC timestamp. */
  timestamp:      string;
  durationMs:     number;
  error:          boolean;
  rootSpan:       TraceSpan;
}

export type ConnectionState = "connecting" | "connected" | "disconnected";

/**
 * A discovered entry point from GET /flow-lens/api/endpoints.
 * Mirrors com.dnlabz.flowlens.starter.model.DiscoveredEndpoint.
 */
export interface DiscoveredEndpoint {
  id:         string;
  type:       EntryPointType;
  /** Same format as TraceRecord.label — used to match traces to endpoints. */
  label:      string;
  className:  string;
  methodName: string;
  /** Display group — controller/consumer/scheduler class name or @Tag value. */
  group:      string;
}

/** HTTP response returned by the /api/proxy route after firing a request. */
export interface HttpResponse {
  status:     number;
  statusText: string;
  headers:    Record<string, string>;
  body:       string;
  durationMs: number;
  /** Set when the proxy itself failed (network error, bad URL, etc.) */
  error?:     string;
}
