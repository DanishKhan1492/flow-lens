package com.dnlabz.flowlens.starter.model;

/**
 * Metadata about a detected entry point discovered at startup by scanning
 * all Spring beans — before any request has been made.
 *
 * <p>The {@link #label} uses exactly the same format as {@link TraceRecord#getLabel()}
 * so the frontend can match traces to their corresponding discovered endpoint.
 */
public class DiscoveredEndpoint {

    /** Stable identifier: "fully.qualified.ClassName#methodName". */
    private final String id;
    private final EntryPointType type;
    /** "GET /api/users" for APIs, "ClassName.method" for consumers / schedulers. */
    private final String label;
    private final String className;
    private final String methodName;
    /**
     * Display group for the UI.  For APIs this is the controller simple class name
     * (or the first {@code @Tag} name if OpenAPI is present).  For consumers and
     * schedulers it is the simple class name of the bean.
     */
    private final String group;

    public DiscoveredEndpoint(String id, EntryPointType type, String label,
                              String className, String methodName, String group) {
        this.id         = id;
        this.type       = type;
        this.label      = label;
        this.className  = className;
        this.methodName = methodName;
        this.group      = group;
    }

    public String         getId()         { return id;         }
    public EntryPointType getType()       { return type;       }
    public String         getLabel()      { return label;      }
    public String         getClassName()  { return className;  }
    public String         getMethodName() { return methodName; }
    public String         getGroup()      { return group;      }
}
