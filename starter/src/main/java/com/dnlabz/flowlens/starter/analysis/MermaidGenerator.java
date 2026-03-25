package com.dnlabz.flowlens.starter.analysis;

import com.dnlabz.flowlens.starter.model.DiscoveredEndpoint;
import com.dnlabz.flowlens.starter.model.EntryPointType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Converts a {@link CallNode} tree (produced by {@link StaticCallAnalyzer}) into
 * a Mermaid {@code sequenceDiagram} definition string ready to be rendered by
 * the FlowLens dashboard.
 *
 * <p>The generated diagram shows:
 * <ul>
 *   <li>The trigger (Client, MessageBroker, or Cron depending on entry-point type)</li>
 *   <li>All application-level call chains with activation boxes</li>
 *   <li>External systems (Kafka, Redis, Database, Cadence …) as terminal participants</li>
 * </ul>
 */
public class MermaidGenerator {

    // Matches both source and target alias in any Mermaid arrow line:
    //   "  A->>+B: label"  "  A-->>-B: "  "  A->>B: label"  "  A-->>B: "
    private static final Pattern ARROW_RE =
        Pattern.compile("^\\s*(\\w+)-+>>[+\\-]?(\\w+):");

    // Pretty display labels for each system kind
    private static final Map<CallNode.Kind, String[]> KIND_DISPLAY = new LinkedHashMap<>();
    static {
        //                  alias (no spaces)     display label
        KIND_DISPLAY.put(CallNode.Kind.REST_CALL,     new String[]{"External_API",   "External API"});
        KIND_DISPLAY.put(CallNode.Kind.KAFKA_PRODUCE, new String[]{"Kafka",          "Kafka"});
        KIND_DISPLAY.put(CallNode.Kind.REDIS,         new String[]{"Redis",          "Redis"});
        KIND_DISPLAY.put(CallNode.Kind.DATABASE,      new String[]{"Database",       "Database"});
        KIND_DISPLAY.put(CallNode.Kind.CADENCE,       new String[]{"Cadence",        "Cadence"});
        KIND_DISPLAY.put(CallNode.Kind.TEMPORAL,      new String[]{"Temporal",       "Temporal"});
        KIND_DISPLAY.put(CallNode.Kind.ELASTICSEARCH, new String[]{"Elasticsearch",  "Elasticsearch"});
        KIND_DISPLAY.put(CallNode.Kind.MONGODB,       new String[]{"MongoDB",        "MongoDB"});
        KIND_DISPLAY.put(CallNode.Kind.GRPC,          new String[]{"gRPC",           "gRPC Service"});
        KIND_DISPLAY.put(CallNode.Kind.MESSAGING,     new String[]{"Messaging",      "Messaging"});
    }

    /**
     * Returns a {@code sequenceDiagram} Mermaid string for the given entry point.
     *
     * @param ep   the discovered endpoint (used for label and type)
     * @param root the call tree root returned by {@link StaticCallAnalyzer#analyze}
     */
    public String generate(DiscoveredEndpoint ep, CallNode root) {
        // Determine external trigger
        String callerAlias   = callerAlias(ep.getType());
        String callerDisplay = callerDisplay(ep.getType());
        String rootAlias     = sanitize(root.getSimpleClass());

        // Collect all participants in first-appearance order
        LinkedHashMap<String, String> participants = new LinkedHashMap<>();
        participants.put(callerAlias, callerDisplay);
        participants.put(rootAlias, root.getSimpleClass());
        collectParticipants(root, participants);

        // Build interaction lines
        List<String> lines = new ArrayList<>();
        String entryLabel = escapeLabel(ep.getLabel());
        lines.add("  " + callerAlias + "->>+" + rootAlias + ": " + entryLabel);
        emitChildren(root, rootAlias, lines, participants);
        lines.add("  " + rootAlias + "-->>-" + callerAlias + ": response");

        // Remove participants that never appear in any arrow (source or target)
        Set<String> referenced = new HashSet<>();
        for (String line : lines) {
            java.util.regex.Matcher m = ARROW_RE.matcher(line);
            if (m.find()) {
                referenced.add(m.group(1));
                referenced.add(m.group(2));
            }
        }
        participants.keySet().retainAll(referenced);

        // Assemble the full diagram
        StringBuilder sb = new StringBuilder("sequenceDiagram\n");
        for (Map.Entry<String, String> p : participants.entrySet()) {
            if (p.getKey().equals(p.getValue())) {
                sb.append("  participant ").append(p.getKey()).append("\n");
            } else {
                sb.append("  participant ").append(p.getKey())
                  .append(" as ").append(p.getValue()).append("\n");
            }
        }
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString().trim();
    }

    // ── Participant collection (pre-pass) ──────────────────────────────────────

    private void collectParticipants(CallNode node, LinkedHashMap<String, String> out) {
        for (CallNode c : node.getChildren()) {
            if (c.getKind() == CallNode.Kind.INTERNAL) {
                out.putIfAbsent(sanitize(c.getSimpleClass()), c.getSimpleClass());
                collectParticipants(c, out);
            } else {
                String alias   = externalAlias(c);
                String display = externalDisplay(c);
                out.putIfAbsent(alias, display);
            }
        }
    }

    // ── Interaction line emission (DFS) ────────────────────────────────────────

    private void emitChildren(CallNode node, String fromAlias,
                               List<String> lines, LinkedHashMap<String, String> participants) {
        for (CallNode child : node.getChildren()) {
            if (child.getKind() == CallNode.Kind.INTERNAL) {
                String alias = sanitize(child.getSimpleClass());
                if (!participants.containsKey(alias)) continue; // safety guard
                lines.add("  " + fromAlias + "->>+" + alias + ": " + escapeLabel(child.getMethod() + "()"));
                emitChildren(child, alias, lines, participants);
                lines.add("  " + alias + "-->>-" + fromAlias + ": ");

            } else {
                String sysAlias   = externalAlias(child);
                String sysDisplay = externalDisplay(child);
                participants.putIfAbsent(sysAlias, sysDisplay);
                String callLbl = escapeLabel(child.getMethod() + "()");
                lines.add("  " + fromAlias + "->>" + sysAlias + ": " + callLbl);
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static String callerAlias(EntryPointType type) {
        if (type == null) return "Client";
        return switch (type) {
            case CONSUMER  -> "MessageBroker";
            case SCHEDULER -> "Cron";
            default        -> "Client";
        };
    }

    private static String callerDisplay(EntryPointType type) {
        if (type == null) return "Client";
        return switch (type) {
            case CONSUMER  -> "Message Broker";
            case SCHEDULER -> "Cron / Timer";
            default        -> "Client";
        };
    }

    /** Sanitise a name so it is a valid Mermaid participant identifier. */
    static String sanitize(String s) {
        if (s == null || s.isBlank()) return "Unknown";
        // Replace any char that is not alphanumeric or _ with _
        return s.replaceAll("[^a-zA-Z0-9_]", "_");
    }

    /** Escape characters that would break Mermaid label parsing. */
    private static String escapeLabel(String s) {
        if (s == null) return "";
        // Quotes and colons inside labels must be replaced
        return s.replace("\"", "'").replace(":", " ");
    }

    /**
     * Returns the Mermaid participant alias (identifier) for an external-system node.
     *
     * <p>When the node carries a meaningful identity (host name, workflow/topic name)
     * that identity is used directly (sanitised).  Otherwise falls back to the
     * generic kind alias from {@link #KIND_DISPLAY} (e.g. {@code External_API}).
     */
    private static String externalAlias(CallNode node) {
        String[] kd = KIND_DISPLAY.get(node.getKind());
        String identity = node.getSimpleClass();
        // If identity equals the generic kindLabel, this node has no enrichment—use kind alias
        if (kd != null && identity.equals(kd[0])) return kd[0];
        // Otherwise the identity IS the meaningful name (host, workflow, topic…)
        return sanitize(identity);
    }

    /**
     * Returns the human-friendly display label for an external-system participant.
     *
     * <p>For enriched nodes the display label adds a kind prefix so the type is still
     * visible: e.g. {@code payments.acme.com} displayed as
     * {@code payments.acme.com  [REST]} or {@code OrderWorkflow  [Cadence]}.
     */
    private static String externalDisplay(CallNode node) {
        String[] kd = KIND_DISPLAY.get(node.getKind());
        String identity = node.getSimpleClass();
        boolean isGeneric = (kd != null && identity.equals(kd[0]));
        // Self-descriptive kinds — return the identity as-is, no suffix needed.
        // DATABASE: "PostgreSQL", "MySQL" etc. speak for themselves.
        // REST_CALL: the identity IS the service name (host or Feign name).
        if (isGeneric || node.getKind() == CallNode.Kind.DATABASE
                      || node.getKind() == CallNode.Kind.REST_CALL) return identity;
        // For Cadence/Temporal workflow names, Kafka topics, etc. add a kind hint
        // so the type of system is still visible in the diagram.
        String kindHint = (kd != null) ? kd[1] : node.getKind().name();
        return identity + " [" + kindHint + "]";
    }
}
