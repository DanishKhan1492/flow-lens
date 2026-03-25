package com.dnlabz.flowlens.starter.analysis;

import org.springframework.aop.support.AopUtils;
import org.springframework.asm.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * Performs lightweight bytecode analysis on an application entry-point method
 * and returns a call tree that describes which internal services and external
 * systems are invoked.
 *
 * <p>Uses Spring's repackaged ASM ({@code org.springframework.asm}) which is
 * always available on the classpath when the starter is present.  No external
 * bytecode-manipulation library is required.
 *
 * <p>Results are memoised per entry-point so repeated diagram requests are
 * fast (code does not change at runtime).
 */
public class StaticCallAnalyzer implements ApplicationContextAware {

    private static final int MAX_DEPTH             = 12;
    private static final int MAX_CHILDREN_PER_NODE  = 30;

    // ── Spring context (used to resolve interfaces → concrete bean classes) ––––––

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.ctx = applicationContext;
    }

    // ── Memoisation cache ─────────────────────────────────────────────────────

    private final Map<String, CallNode> cache = new ConcurrentHashMap<>();

    /** Lazily resolved display name for the primary database (read once from application properties). */
    private volatile String databaseDisplayName;

    // ── Known external-service registry (slash-form owner → Kind) ─────────────

    private static final Map<String, CallNode.Kind> OWNER_TO_KIND = new LinkedHashMap<>();
    static {
        // REST
        OWNER_TO_KIND.put("org/springframework/web/client/RestTemplate",       CallNode.Kind.REST_CALL);
        OWNER_TO_KIND.put("org/springframework/web/client/RestClient",          CallNode.Kind.REST_CALL);
        OWNER_TO_KIND.put("org/springframework/web/reactive/function/client/WebClient", CallNode.Kind.REST_CALL);
        OWNER_TO_KIND.put("java/net/http/HttpClient",                            CallNode.Kind.REST_CALL);
        OWNER_TO_KIND.put("okhttp3/OkHttpClient",                               CallNode.Kind.REST_CALL);
        OWNER_TO_KIND.put("okhttp3/Call",                                        CallNode.Kind.REST_CALL);
        OWNER_TO_KIND.put("retrofit2/Retrofit",                                  CallNode.Kind.REST_CALL);
        OWNER_TO_KIND.put("feign/Client",                                        CallNode.Kind.REST_CALL);
        // Kafka
        OWNER_TO_KIND.put("org/springframework/kafka/core/KafkaTemplate",       CallNode.Kind.KAFKA_PRODUCE);
        OWNER_TO_KIND.put("org/apache/kafka/clients/producer/KafkaProducer",    CallNode.Kind.KAFKA_PRODUCE);
        OWNER_TO_KIND.put("org/apache/kafka/clients/producer/Producer",         CallNode.Kind.KAFKA_PRODUCE);
        // Redis
        OWNER_TO_KIND.put("org/springframework/data/redis/core/RedisTemplate",  CallNode.Kind.REDIS);
        OWNER_TO_KIND.put("org/springframework/data/redis/core/StringRedisTemplate", CallNode.Kind.REDIS);
        OWNER_TO_KIND.put("org/springframework/data/redis/core/ReactiveRedisTemplate", CallNode.Kind.REDIS);
        OWNER_TO_KIND.put("redis/clients/jedis/Jedis",                           CallNode.Kind.REDIS);
        OWNER_TO_KIND.put("redis/clients/jedis/JedisPool",                       CallNode.Kind.REDIS);
        OWNER_TO_KIND.put("io/lettuce/core/api/StatefulRedisConnection",         CallNode.Kind.REDIS);
        OWNER_TO_KIND.put("org/redisson/api/RedissonClient",                     CallNode.Kind.REDIS);
        // Database (direct)
        OWNER_TO_KIND.put("org/springframework/jdbc/core/JdbcTemplate",         CallNode.Kind.DATABASE);
        OWNER_TO_KIND.put("org/springframework/jdbc/core/namedparam/NamedParameterJdbcTemplate", CallNode.Kind.DATABASE);
        OWNER_TO_KIND.put("javax/persistence/EntityManager",                    CallNode.Kind.DATABASE);
        OWNER_TO_KIND.put("jakarta/persistence/EntityManager",                  CallNode.Kind.DATABASE);
        OWNER_TO_KIND.put("org/hibernate/Session",                               CallNode.Kind.DATABASE);
        OWNER_TO_KIND.put("org/springframework/data/jpa/repository/support/SimpleJpaRepository", CallNode.Kind.DATABASE);
        // Cadence
        OWNER_TO_KIND.put("com/uber/cadence/client/WorkflowClient",             CallNode.Kind.CADENCE);
        OWNER_TO_KIND.put("com/uber/cadence/workflow/Workflow",                  CallNode.Kind.CADENCE);
        OWNER_TO_KIND.put("com/uber/cadence/activity/Activity",                  CallNode.Kind.CADENCE);
        // Temporal
        OWNER_TO_KIND.put("io/temporal/client/WorkflowClient",                  CallNode.Kind.TEMPORAL);
        OWNER_TO_KIND.put("io/temporal/workflow/Workflow",                       CallNode.Kind.TEMPORAL);
        // Elasticsearch
        OWNER_TO_KIND.put("org/springframework/data/elasticsearch/core/ElasticsearchOperations", CallNode.Kind.ELASTICSEARCH);
        OWNER_TO_KIND.put("org/springframework/data/elasticsearch/core/ReactiveElasticsearchOperations", CallNode.Kind.ELASTICSEARCH);
        OWNER_TO_KIND.put("co/elastic/clients/elasticsearch/ElasticsearchClient", CallNode.Kind.ELASTICSEARCH);
        // MongoDB
        OWNER_TO_KIND.put("org/springframework/data/mongodb/core/MongoTemplate", CallNode.Kind.MONGODB);
        OWNER_TO_KIND.put("org/springframework/data/mongodb/core/ReactiveMongoTemplate", CallNode.Kind.MONGODB);
        // RabbitMQ / AMQP
        OWNER_TO_KIND.put("org/springframework/amqp/rabbit/core/RabbitTemplate", CallNode.Kind.MESSAGING);
        OWNER_TO_KIND.put("org/springframework/amqp/core/AmqpTemplate",         CallNode.Kind.MESSAGING);
        // AWS SQS / SNS
        OWNER_TO_KIND.put("io/awspring/cloud/sqs/operations/SqsTemplate",       CallNode.Kind.MESSAGING);
        OWNER_TO_KIND.put("software/amazon/awssdk/services/sqs/SqsClient",     CallNode.Kind.MESSAGING);
        OWNER_TO_KIND.put("software/amazon/awssdk/services/sns/SnsClient",     CallNode.Kind.MESSAGING);
        OWNER_TO_KIND.put("com/amazonaws/services/sqs/AmazonSQS",              CallNode.Kind.MESSAGING);
        OWNER_TO_KIND.put("com/amazonaws/services/sns/AmazonSNS",              CallNode.Kind.MESSAGING);
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Returns the call tree for the given entry point.  Results are cached so
     * subsequent calls for the same entry point are instant.
     */
    public CallNode analyze(String fqClassName, String methodName) {
        String key = fqClassName + "#" + methodName;
        return cache.computeIfAbsent(key, k -> doAnalyze(fqClassName, methodName));
    }

    // ── Internal analysis ──────────────────────────────────────────────────────

    private CallNode doAnalyze(String fqClassName, String methodName) {
        // Resolve to concrete class in case the entry point is an interface or proxy
        String concreteFq = resolveConcrete(fqClassName);
        CallNode root = new CallNode(simpleClassName(concreteFq), methodName, CallNode.Kind.ENTRY, null);
        Set<String> visited = new HashSet<>();
        visited.add(concreteFq + "#" + methodName);
        Map<String, CallNode.Kind> fieldKinds = scanFieldKindsHierarchy(concreteFq);
        populateChildren(root, concreteFq, concreteFq, methodName, visited, 0, fieldKinds);
        return root;
    }

    /**
     * Scans field types from the FULL class hierarchy (class + all superclasses)
     * to build a map of slash-form type → external-service kind.
     * This ensures repositories / templates injected in abstract parent classes
     * are also detected.
     */
    private Map<String, CallNode.Kind> scanFieldKindsHierarchy(String fqClassName) {
        Map<String, CallNode.Kind> result = new HashMap<>();
        String current = fqClassName;
        Set<String> visited = new HashSet<>();
        while (current != null && !isJvmBuiltin(current.replace('.', '/')) && visited.add(current)) {
            result.putAll(scanFieldKinds(current));
            // Walk up the superclass chain via reflection
            try {
                Class<?> cls = Class.forName(current, false, Thread.currentThread().getContextClassLoader());
                Class<?> superCls = cls.getSuperclass();
                current = (superCls != null) ? superCls.getName() : null;
            } catch (Exception ignored) {
                break;
            }
        }
        return result;
    }

    /**
     * Scans declared fields of a SINGLE class and maps their slash-form type
     * to a detected external-service kind.
     */
    private Map<String, CallNode.Kind> scanFieldKinds(String fqClassName) {
        byte[] bytecode = loadBytecode(fqClassName);
        if (bytecode == null) return Collections.emptyMap();
        Map<String, CallNode.Kind> result = new HashMap<>();
        ClassReader cr = new ClassReader(bytecode);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                // descriptor is like "Lcom/example/UserRepository;"
                if (descriptor.startsWith("L") && descriptor.endsWith(";")) {
                    String slashType = descriptor.substring(1, descriptor.length() - 1);
                    String dotType   = slashType.replace('/', '.');
                    // 1. Global external-service check
                    CallNode.Kind k = detectExternalKind(slashType);
                    if (k != null) { result.put(slashType, k); return null; }
                    // 2. Spring Data repository
                    if (isSpringDataRepository(dotType)) {
                        result.put(slashType, CallNode.Kind.DATABASE);
                        return null;
                    }
                    // 3. @Repository annotation
                    if (hasRepositoryAnnotation(dotType)) {
                        result.put(slashType, CallNode.Kind.DATABASE);
                        return null;
                    }
                    // 4. Feign client
                    if (isFeignClient(dotType)) {
                        result.put(slashType, CallNode.Kind.REST_CALL);
                    }
                }
                return null;
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG | ClassReader.SKIP_CODE);
        return result;
    }

    private void populateChildren(CallNode parent, String ownerClass,
                                  String fqClassName, String methodName,
                                  Set<String> visited, int depth,
                                  Map<String, CallNode.Kind> fieldKinds) {
        if (depth >= MAX_DEPTH) return;

        // Resolve to concrete class before loading bytecode
        String concreteFq = resolveConcrete(fqClassName);
        byte[] bytecode = loadBytecode(concreteFq);
        if (bytecode == null) return;

        Map<String, String> valueUrls = scanValueUrlFields(bytecode);
        List<MethodCallInfo> calls = collectMethodCalls(bytecode, methodName, valueUrls);
        for (MethodCallInfo call : calls) {
            if (parent.getChildren().size() >= MAX_CHILDREN_PER_NODE) break;
            processCall(parent, ownerClass, call, visited, depth, fieldKinds);
        }
    }

    private void processCall(CallNode parent, String ownerClass, MethodCallInfo call,
                              Set<String> visited, int depth,
                              Map<String, CallNode.Kind> fieldKinds) {
        // Skip constructors and static initialisers
        if ("<init>".equals(call.name) || "<clinit>".equals(call.name)) return;

        String owner   = call.owner;   // slash form
        String dotForm = owner.replace('/', '.');

        // 1. JVM built-ins → ignore
        if (isJvmBuiltin(owner)) return;

        // 2. Known external service from field scan (covers user-defined repo interfaces)
        CallNode.Kind fieldKind = fieldKinds.get(owner);
        if (fieldKind != null) {
            String fallback;
            if (fieldKind == CallNode.Kind.DATABASE) {
                fallback = getDatabaseName();
            } else if (fieldKind == CallNode.Kind.REST_CALL && isFeignClient(dotForm)) {
                String svcName = feignClientServiceName(dotForm);
                fallback = svcName != null ? svcName : simpleClassName(dotForm);
            } else {
                fallback = simpleClassName(dotForm);
            }
            addExternalNode(parent, call, fieldKind, fallback, call.ldcArg());
            return;
        }

        // 3. Known external service (global table – exact or prefix match)
        CallNode.Kind extKind = detectExternalKind(owner);
        if (extKind != null) {
            String fallback = (extKind == CallNode.Kind.DATABASE)
                ? getDatabaseName() : kindLabel(extKind);
            addExternalNode(parent, call, extKind, fallback, call.ldcArg());
            return;
        }

        // 4. Framework / library class that is NOT a known external service.
        //    Before skipping entirely, check if this is a super-class / library
        //    method whose name implies a specific external-system intent (e.g.
        //    super.save(), super.update(), super.publish() called on a base
        //    repository or messaging class from a dependency JAR).
        if (isFrameworkClass(owner)) {
            CallNode.Kind intentKind = detectIntentFromMethodName(call.name, owner);
            if (intentKind != null) {
                String label = (intentKind == CallNode.Kind.DATABASE) ? getDatabaseName()
                             : kindLabel(intentKind);
                addExternalNode(parent, call, intentKind, label, call.ldcArg());
            }
            return;
        }

        // 5. Spring Data repository interface → DATABASE leaf (collapsed to single named participant)
        if (isSpringDataRepository(dotForm)) {
            addExternalNode(parent, call, CallNode.Kind.DATABASE, getDatabaseName(), null);
            return;
        }

        // 6. @Repository DAO → DATABASE leaf
        if (hasRepositoryAnnotation(dotForm)) {
            addExternalNode(parent, call, CallNode.Kind.DATABASE, getDatabaseName(), null);
            return;
        }

        // 7. Feign client → REST_CALL leaf (use @FeignClient name/url when available)
        if (isFeignClient(dotForm)) {
            String svcName = feignClientServiceName(dotForm);
            String label   = svcName != null ? svcName : simpleClassName(dotForm);
            addExternalNode(parent, call, CallNode.Kind.REST_CALL, label, null);
            return;
        }

        // 8. gRPC stub → GRPC leaf
        if (isGrpcStub(owner)) {
            addExternalNode(parent, call, CallNode.Kind.GRPC, simpleClassName(dotForm), null);
            return;
        }

        // Resolve interface / abstract type to its concrete Spring bean class
        String concreteDotForm = resolveConcrete(dotForm);

        // 9. Config-properties bean (reads from application.properties) → skip silently.
        //    These are pure data-holders; showing them adds noise, not insight.
        if (isConfigBean(concreteDotForm)) return;

        // 10. Self-call (method on the same bean) → flatten completely.
        //    Do NOT create a new participant node; just recurse into the helper method
        //    and surface any external/bean calls it makes under the current parent.
        boolean isSelfCall = concreteDotForm.equals(ownerClass) || dotForm.equals(ownerClass);
        if (isSelfCall) {
            String visitKey = concreteDotForm + "#" + call.name;
            if (visited.contains(visitKey)) return;
            visited.add(visitKey);
            populateChildren(parent, ownerClass, concreteDotForm, call.name, visited, depth + 1, fieldKinds);
            return;
        }

        // 10. Call to another Spring bean → show as INTERNAL participant, recurse
        if (isApplicationBean(concreteDotForm)) {
            String visitKey = concreteDotForm + "#" + call.name;
            if (visited.contains(visitKey)) return;
            visited.add(visitKey);
            String concreteName = simpleClassName(concreteDotForm);
            CallNode child = new CallNode(concreteName, call.name, CallNode.Kind.INTERNAL, null);
            parent.addChild(child);
            Map<String, CallNode.Kind> childFields = scanFieldKindsHierarchy(concreteDotForm);
            populateChildren(child, concreteDotForm, concreteDotForm, call.name, visited, depth + 1, childFields);
            return;
        }

        // 11. Non-bean application class (utility, helper, builder, DTO, etc.)
        //     → silently flatten: recurse through it but attribute any calls to the
        //       current parent participant.  This surfaces any external/bean calls
        //       hidden inside helper objects without polluting the diagram.
        String visitKey = concreteDotForm + "#" + call.name;
        if (visited.contains(visitKey)) return;
        visited.add(visitKey);
        // Merge the non-bean class's field references so external-service detection works
        Map<String, CallNode.Kind> mergedFields = new HashMap<>(fieldKinds);
        mergedFields.putAll(scanFieldKindsHierarchy(concreteDotForm));
        populateChildren(parent, ownerClass, concreteDotForm, call.name, visited, depth + 1, mergedFields);
    }

    private void addExternalNode(CallNode parent, MethodCallInfo call,
                                 CallNode.Kind kind, String fallbackLabel, String ldcArg) {
        String detail   = meaningfulExternalLabel(kind, ldcArg);
        String identity = (detail != null) ? detail : fallbackLabel;
        List<CallNode> children = parent.getChildren();

        if (detail != null) {
            // We have a specific name (e.g. host from URI, workflow name, topic).
            // Remove any generic placeholder that an earlier builder-chain call
            // (e.g. post() before uri()) may have already added for this kind.
            children.removeIf(c -> c.getKind() == kind && c.getSimpleClass().equals(fallbackLabel));
        } else {
            // No enrichment available — skip this call if a more-specific named node
            // for the same kind already exists (avoids polluting the diagram with
            // intermediate builder calls like body(), retrieve(), exchange()).
            boolean hasNamedNode = children.stream()
                .anyMatch(c -> c.getKind() == kind && !c.getSimpleClass().equals(fallbackLabel));
            if (hasNamedNode) return;
        }

        boolean alreadyPresent = children.stream()
            .anyMatch(c -> c.getSimpleClass().equals(identity) && c.getKind() == kind);
        if (!alreadyPresent) {
            parent.addChild(new CallNode(identity, call.name(), kind, detail));
        }
    }

    /**
     * Derives a human-readable name for an external-system call.
     *
     * <p>Rules (applied in order):
     * <ol>
     *   <li><b>REST_CALL + URI string</b>: extract the host from the URI
     *       (e.g. {@code https://payments.acme.com/charge} → {@code payments.acme.com}).</li>
     *   <li><b>CADENCE / TEMPORAL + stub/workflow name string</b>: use the
     *       first argument literally (e.g. {@code "OrderWorkflow"} →
     *       {@code OrderWorkflow}).</li>
     *   <li><b>KAFKA_PRODUCE + topic string</b>: prefix with {@code topic:}
     *       (e.g. {@code "order-events"} → {@code topic:order-events}).</li>
     *   <li><b>MESSAGING + queue/exchange string</b>: prefix with
     *       {@code queue:}.</li>
     *   <li>Otherwise return {@code null} so the caller uses its fallback label.</li>
     * </ol>
     */
    private static String meaningfulExternalLabel(CallNode.Kind kind, String ldcArg) {
        if (ldcArg == null || ldcArg.isBlank()) return null;
        String arg = ldcArg.trim();

        switch (kind) {
            case REST_CALL -> {
                // Only use absolute URIs — extract the host so the participant gets a
                // meaningful name (e.g. "payments.acme.com").
                // Relative path fragments ("/api/users") are NOT used: they reveal
                // nothing about WHICH service is being called.
                String host = extractHost(arg);
                if (host != null) return host;
            }
            case CADENCE, TEMPORAL -> {
                // First arg is typically the workflow/activity type name
                if (arg.matches("[A-Za-z][A-Za-z0-9_$.]*")) return arg;
            }
            case KAFKA_PRODUCE -> {
                // Topic name
                if (!arg.contains(" ") && arg.length() <= 120) return "topic:" + arg;
            }
            case MESSAGING -> {
                // Queue / exchange / routing-key name
                if (!arg.contains(" ") && arg.length() <= 120) return "queue:" + arg;
            }
            default -> { /* no enrichment for DATABASE, REDIS, etc. */ }
        }
        return null;
    }

    /**
     * Extracts the host (domain) portion of a URI string.
     * Handles {@code http://}, {@code https://}, {@code grpc://}, or any scheme.
     * Returns {@code null} if the string doesn’t look like a URI.
     */
    private static String extractHost(String uri) {
        try {
            // Quick sanity check — must contain ://
            int schemeEnd = uri.indexOf("://");
            if (schemeEnd < 0) return null;
            String rest = uri.substring(schemeEnd + 3);
            // Strip userinfo (user:pass@)
            int at = rest.indexOf('@');
            if (at >= 0) rest = rest.substring(at + 1);
            // Strip path / query / fragment
            int slash = rest.indexOf('/');
            if (slash >= 0) rest = rest.substring(0, slash);
            int query = rest.indexOf('?');
            if (query >= 0) rest = rest.substring(0, query);
            // Strip port
            int colon = rest.lastIndexOf(':');
            if (colon >= 0) rest = rest.substring(0, colon);
            // Validate: host must not be empty and must look like a domain or IP
            if (rest.isBlank() || rest.contains(" ")) return null;
            return rest;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Reads the {@code @FeignClient} annotation on a Feign interface and returns
     * the most descriptive service identifier available:
     * <ol>
     *   <li>{@code url} attribute — if set, the host is extracted from it.</li>
     *   <li>{@code name} / {@code value} attribute — the logical service name.</li>
     * </ol>
     * Returns {@code null} if the class is not a Feign client or no useful value found.
     */
    private static String feignClientServiceName(String dotForm) {
        try {
            Class<?> cls = Class.forName(dotForm, false,
                Thread.currentThread().getContextClassLoader());
            for (java.lang.annotation.Annotation ann : cls.getAnnotations()) {
                if (!ann.annotationType().getName().endsWith("FeignClient")) continue;
                // url takes priority — extract host so it looks like a real endpoint
                try {
                    java.lang.reflect.Method m = ann.annotationType().getDeclaredMethod("url");
                    String url = (String) m.invoke(ann);
                    if (url != null && !url.isBlank()) {
                        String host = extractHost(url);
                        return host != null ? host : url;
                    }
                } catch (Exception ignored) {}
                // Logical service name (name / value / serviceId)
                for (String attr : new String[]{"name", "value", "serviceId"}) {
                    try {
                        java.lang.reflect.Method m = ann.annotationType().getDeclaredMethod(attr);
                        String v = (String) m.invoke(ann);
                        if (v != null && !v.isBlank()) return v;
                    } catch (Exception ignored) {}
                }
            }
        } catch (LinkageError | Exception ignored) {}
        return null;
    }

    /**
     * Scans the class bytecode for {@code String} fields annotated with
     * {@code @Value("${property.key}")} and resolves each property from the
     * Spring {@link Environment}.  Only fields whose resolved value looks like
     * an absolute URL (contains {@code ://}) are included.
     *
     * <p>The returned map (fieldName → resolvedUrl) is later used by
     * {@link #collectMethodCalls} to surface the target service name even when
     * the URL is supplied via an injected field rather than a string literal.
     */
    private Map<String, String> scanValueUrlFields(byte[] bytecode) {
        if (ctx == null) return Collections.emptyMap();
        org.springframework.core.env.Environment env;
        try { env = ctx.getEnvironment(); } catch (Exception e) { return Collections.emptyMap(); }

        Map<String, String> result = new HashMap<>();
        new ClassReader(bytecode).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if (!"Ljava/lang/String;".equals(descriptor)) return null; // only String fields
                final String fieldName = name;
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override
                    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
                        if (!desc.equals("Lorg/springframework/beans/factory/annotation/Value;"))
                            return null;
                        return new AnnotationVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(String attrName, Object attrValue) {
                                if (!"value".equals(attrName)) return;
                                if (!(attrValue instanceof String)) return;
                                String key = extractPropertyKey(((String) attrValue).trim());
                                if (key == null) return;
                                try {
                                    String resolved = env.getProperty(key);
                                    if (resolved != null && extractHost(resolved) != null) {
                                        result.put(fieldName, resolved);
                                    }
                                } catch (Exception ignored) {}
                            }
                        };
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return result;
    }

    /** Extracts the property key from a Spring {@code ${key}} or {@code ${key:default}} expression. */
    private static String extractPropertyKey(String expr) {
        if (expr.startsWith("${") && expr.contains("}")) {
            String inner = expr.substring(2, expr.lastIndexOf('}'));
            int colon = inner.indexOf(':');
            return (colon >= 0 ? inner.substring(0, colon) : inner).trim();
        }
        return null;
    }

    // ── Bytecode helpers ───────────────────────────────────────────────────────

    /** Collect all INVOKE* instructions for ALL overloads of {@code methodName}. */
    private List<MethodCallInfo> collectMethodCalls(byte[] bytecode, String methodName,
                                                     Map<String, String> fieldValueUrls) {
        List<MethodCallInfo> calls = new ArrayList<>();
        ClassReader cr = new ClassReader(bytecode);
        cr.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if (!name.equals(methodName)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    // Track the last string constant pushed onto the operand stack
                    // so that calls like .uri("https://...") or
                    // newUntypedWorkflowStub("OrderWorkflow") carry their argument.
                    // Also picks up base-URL fields annotated with @Value("${...}").
                    private String lastLdcString = null;

                    @Override
                    public void visitLdcInsn(Object cst) {
                        lastLdcString = (cst instanceof String) ? (String) cst : null;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        calls.add(new MethodCallInfo(owner, name, descriptor, lastLdcString));
                        // Reset: the string was consumed as an argument to this call.
                        lastLdcString = null;
                    }

                    // GETFIELD of a known @Value URL field → surface the resolved URL.
                    @Override
                    public void visitFieldInsn(int opcode, String owner2, String fieldName, String d) {
                        if (opcode == Opcodes.GETFIELD && fieldValueUrls.containsKey(fieldName)) {
                            lastLdcString = fieldValueUrls.get(fieldName);
                        } else {
                            lastLdcString = null;
                        }
                    }

                    // String concatenation via invokedynamic (Java 9+) — preserve a URL base
                    // so the host can still be extracted from "baseUrl + /path" patterns.
                    @Override
                    public void visitInvokeDynamicInsn(String n2, String d, Handle h, Object... a) {
                        if (!("makeConcatWithConstants".equals(n2)
                              && lastLdcString != null && extractHost(lastLdcString) != null)) {
                            lastLdcString = null;
                        }
                    }

                    // Any other instruction that produces a value invalidates the
                    // pending constant (the stack slot is no longer just our string).
                    @Override public void visitInsn(int opcode)            { lastLdcString = null; }
                    @Override public void visitIntInsn(int o, int op)      { lastLdcString = null; }
                    @Override public void visitVarInsn(int o, int v)       { lastLdcString = null; }
                    @Override public void visitTypeInsn(int o, String t)   { lastLdcString = null; }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return calls;
    }

    private byte[] loadBytecode(String fqClassName) {
        String resource = fqClassName.replace('.', '/') + ".class";
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = getClass().getClassLoader();
        try (InputStream is = cl.getResourceAsStream(resource)) {
            return is == null ? null : is.readAllBytes();
        } catch (IOException ignored) {
            return null;
        }
    }

    // ── Classification helpers ─────────────────────────────────────────────────

    private static CallNode.Kind detectExternalKind(String owner) {
        // Exact match
        CallNode.Kind k = OWNER_TO_KIND.get(owner);
        if (k != null) return k;
        // Prefix match (handles inner classes like WebClient$RequestBodySpec)
        for (Map.Entry<String, CallNode.Kind> e : OWNER_TO_KIND.entrySet()) {
            if (owner.startsWith(e.getKey())) return e.getValue();
        }
        return null;
    }

    private static boolean isJvmBuiltin(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/")
            || owner.startsWith("jakarta/lang") || owner.startsWith("sun/")
            || owner.startsWith("jdk/") || owner.startsWith("com/sun/");
    }

    private static boolean isFrameworkClass(String owner) {
        return owner.startsWith("org/springframework/")
            || owner.startsWith("org/hibernate/")
            || owner.startsWith("org/apache/")
            || owner.startsWith("io/netty/")
            || owner.startsWith("reactor/")
            || owner.startsWith("org/slf4j/")
            || owner.startsWith("ch/qos/")
            || owner.startsWith("com/fasterxml/")
            || owner.startsWith("io/micrometer/")
            || owner.startsWith("org/aspectj/")
            || owner.startsWith("net/bytebuddy/")
            || owner.startsWith("com/dnlabz/flowlens/")
            || owner.startsWith("io/grpc/internal/")
            || owner.startsWith("io/grpc/stub/")
            // Crypto / PKI
            || owner.startsWith("org/bouncycastle/")
            // Common utility / integration libraries
            || owner.startsWith("com/google/common/")
            || owner.startsWith("com/google/protobuf/")
            || owner.startsWith("org/mapstruct/")
            || owner.startsWith("org/modelmapper/")
            || owner.startsWith("io/vavr/")
            || owner.startsWith("org/joda/")
            || owner.startsWith("com/amazonaws/")
            || owner.startsWith("software/amazon/")
            || owner.startsWith("io/awspring/")
            || owner.startsWith("org/quartz/")
            || owner.startsWith("com/zaxxer/");
    }

    /**
     * Infers an external-system {@link CallNode.Kind} from a method name when
     * the callee class itself is a library/framework type (and therefore not in
     * {@link #OWNER_TO_KIND}).  This handles patterns such as:
     * <ul>
     *   <li>{@code super.save()} / {@code super.update()} on a base-repository class → DATABASE</li>
     *   <li>{@code super.send()} / {@code super.publish()} on a base-messaging class → KAFKA/MESSAGING</li>
     *   <li>{@code super.set()} / {@code super.get()} on a base-cache class → REDIS</li>
     * </ul>
     * Returns {@code null} when the method name carries no recognisable intent.
     */
    private static CallNode.Kind detectIntentFromMethodName(String methodName, String ownerSlash) {
        // ── Persistence / database intent ─────────────────────────────────────
        // Common method names that almost always mean "write to the primary store"
        if (methodName.equals("save")
         || methodName.equals("saveAll")
         || methodName.equals("saveAndFlush")
         || methodName.equals("persist")
         || methodName.equals("merge")
         || methodName.equals("flush")
         || methodName.equals("create")     // only when on a repo/DAO base
         || methodName.equals("update")
         || methodName.equals("updateAll")
         || methodName.equals("delete")
         || methodName.equals("deleteById")
         || methodName.equals("deleteAll")
         || methodName.equals("deleteInBatch")
         || methodName.equals("remove")
         || methodName.equals("insert")
         || methodName.equals("upsert")
         || methodName.startsWith("findAll")
         || methodName.startsWith("findBy")
         || methodName.startsWith("existsBy")
         || methodName.startsWith("countBy")
         || methodName.startsWith("deleteBy")) {
            // Confirm the owner is a data-layer class by checking the package.
            // We don't want to mark random library "create()" or "delete()" methods.
            if (isDataLayerOwner(ownerSlash)) return CallNode.Kind.DATABASE;
        }

        // ── Kafka / messaging intent ──────────────────────────────────────────
        if (methodName.equals("send")
         || methodName.equals("sendDefault")
         || methodName.equals("sendMessage")
         || methodName.equals("publish")
         || methodName.equals("publishMessage")
         || methodName.equals("produce")) {
            if (isMessagingOwner(ownerSlash)) return CallNode.Kind.KAFKA_PRODUCE;
        }

        // ── Redis / cache intent ──────────────────────────────────────────────
        if (methodName.equals("set")
         || methodName.equals("get")
         || methodName.equals("getAndSet")
         || methodName.equals("setIfAbsent")
         || methodName.equals("expire")
         || methodName.equals("evict")
         || methodName.equals("put")
         || methodName.equals("putAll")
         || methodName.equals("putIfAbsent")) {
            if (isCacheOwner(ownerSlash)) return CallNode.Kind.REDIS;
        }

        return null;
    }

    private static boolean isDataLayerOwner(String ownerSlash) {
        return ownerSlash.contains("repository")
            || ownerSlash.contains("Repository")
            || ownerSlash.contains("dao")
            || ownerSlash.contains("Dao")
            || ownerSlash.contains("persistence")
            || ownerSlash.contains("jpa")
            || ownerSlash.contains("hibernate")
            || ownerSlash.contains("jdbc")
            || ownerSlash.contains("mongo")
            || ownerSlash.contains("elastic")
            || ownerSlash.contains("cassandra")
            || ownerSlash.contains("r2dbc");
    }

    private static boolean isMessagingOwner(String ownerSlash) {
        return ownerSlash.contains("kafka")
            || ownerSlash.contains("Kafka")
            || ownerSlash.contains("rabbit")
            || ownerSlash.contains("Rabbit")
            || ownerSlash.contains("amqp")
            || ownerSlash.contains("Amqp")
            || ownerSlash.contains("sqs")
            || ownerSlash.contains("Sqs")
            || ownerSlash.contains("sns")
            || ownerSlash.contains("messaging")
            || ownerSlash.contains("Messaging");
    }

    private static boolean isCacheOwner(String ownerSlash) {
        return ownerSlash.contains("redis")
            || ownerSlash.contains("Redis")
            || ownerSlash.contains("cache")
            || ownerSlash.contains("Cache")
            || ownerSlash.contains("jedis")
            || ownerSlash.contains("lettuce")
            || ownerSlash.contains("redisson");
    }

    /**
     * Returns a human-readable label for an external-system kind.
     * Used when the call carries no specific topic/URL/name to display.
     */
    private static String kindLabel(CallNode.Kind kind) {
        return switch (kind) {
            case DATABASE      -> "Database";
            case REDIS         -> "Redis";
            case KAFKA_PRODUCE -> "Kafka";
            case MESSAGING     -> "Messaging";
            case CADENCE       -> "Cadence";
            case TEMPORAL      -> "Temporal";
            case ELASTICSEARCH -> "Elasticsearch";
            case MONGODB       -> "MongoDB";
            case GRPC          -> "gRPC";
            default            -> kind.name();
        };
    }

    private static boolean isSpringDataRepository(String dotForm) {
        try {
            Class<?> cls = Class.forName(dotForm, false,
                Thread.currentThread().getContextClassLoader());
            return implementsSpringDataRepo(cls, new HashSet<>());
        } catch (LinkageError | Exception ignored) {
            return false;
        }
    }

    private static boolean implementsSpringDataRepo(Class<?> cls, Set<String> seen) {
        if (cls == null) return false;
        String name = cls.getName();
        if (!seen.add(name)) return false;
        // Match any interface in any org.springframework.data sub-package
        // (covers repository, jpa.repository, mongodb.repository, r2dbc.repository, etc.)
        if (name.startsWith("org.springframework.data.")) return true;
        for (Class<?> iface : cls.getInterfaces()) {
            if (implementsSpringDataRepo(iface, seen)) return true;
        }
        return implementsSpringDataRepo(cls.getSuperclass(), seen);
    }

    private static boolean isFeignClient(String dotForm) {
        try {
            Class<?> cls = Class.forName(dotForm, false,
                Thread.currentThread().getContextClassLoader());
            for (java.lang.annotation.Annotation ann : cls.getAnnotations()) {
                String annName = ann.annotationType().getName();
                if (annName.equals("org.springframework.cloud.openfeign.FeignClient")
                 || annName.equals("feign.RequestLine")) {
                    return true;
                }
            }
        } catch (LinkageError | Exception ignored) {
            // ignore
        }
        return false;
    }

    private static boolean hasRepositoryAnnotation(String dotForm) {
        try {
            Class<?> cls = Class.forName(dotForm, false,
                Thread.currentThread().getContextClassLoader());
            for (java.lang.annotation.Annotation ann : cls.getAnnotations()) {
                String annName = ann.annotationType().getName();
                if (annName.equals("org.springframework.stereotype.Repository")
                 || annName.equals("javax.persistence.Repository")
                 || annName.equals("jakarta.persistence.Repository")) {
                    return true;
                }
            }
        } catch (LinkageError | Exception ignored) {
            // ignore
        }
        return false;
    }

    private static boolean isGrpcStub(String owner) {
        String lc = owner.toLowerCase();
        return lc.contains("/grpc/") && (owner.endsWith("Stub")
            || owner.endsWith("BlockingStub")
            || owner.endsWith("FutureStub")
            || owner.endsWith("StubImpl"));
    }

    /**
     * Returns {@code true} when {@code dotForm} is a config/properties bean whose
     * sole purpose is exposing values from {@code application.properties}/YAML
     * or wiring up infrastructure — NOT meaningful business-logic participants.
     *
     * <p>Matches:
     * <ul>
     *   <li>Classes annotated with {@code @ConfigurationProperties}</li>
     *   <li>Classes annotated with {@code @Configuration} (bean-factory classes)</li>
     *   <li>Simple name ends with {@code Config}, {@code Configuration},
     *       {@code Properties}, {@code Settings}, {@code Props}, or {@code Conf}
     *       (case-insensitive)</li>
     * </ul>
     */
    private static boolean isConfigBean(String dotForm) {
        try {
            Class<?> cls = Class.forName(dotForm, false,
                Thread.currentThread().getContextClassLoader());
            for (java.lang.annotation.Annotation ann : cls.getAnnotations()) {
                String n = ann.annotationType().getName();
                if (n.equals("org.springframework.boot.context.properties.ConfigurationProperties")
                 || n.equals("org.springframework.context.annotation.Configuration")) {
                    return true;
                }
            }
            String simple = cls.getSimpleName().toLowerCase();
            return simple.endsWith("config")
                || simple.endsWith("configuration")
                || simple.endsWith("properties")
                || simple.endsWith("settings")
                || simple.endsWith("props")
                || simple.endsWith("conf");
        } catch (LinkageError | Exception ignored) {}
        // Fallback: check simple name from dotForm string
        String simple = dotForm.contains(".")
            ? dotForm.substring(dotForm.lastIndexOf('.') + 1).toLowerCase()
            : dotForm.toLowerCase();
        return simple.endsWith("config")
            || simple.endsWith("configuration")
            || simple.endsWith("properties")
            || simple.endsWith("settings")
            || simple.endsWith("props")
            || simple.endsWith("conf");
    }

    // ── Utility ────────────────────────────────────────────────────────────────

    /**
     * If {@code dotForm} refers to an interface or abstract class, looks up a
     * concrete Spring bean implementing it via the ApplicationContext and returns
     * the concrete class name.  Falls back to {@code dotForm} if not resolvable.
     */
    private String resolveConcrete(String dotForm) {
        if (ctx == null) return dotForm;
        try {
            Class<?> cls = Class.forName(dotForm, false,
                Thread.currentThread().getContextClassLoader());
            if (!cls.isInterface() && !Modifier.isAbstract(cls.getModifiers())) {
                return dotForm; // already concrete
            }
            // Find beans of this type and pick the first concrete implementation
            Map<String, ?> beans = ctx.getBeansOfType(cls, false, false);
            for (Object bean : beans.values()) {
                Class<?> target = AopUtils.getTargetClass(bean);
                String pkg = target.getPackageName();
                // Skip framework / FlowLens proxy classes
                if (!pkg.startsWith("org.springframework") && !pkg.startsWith("com.dnlabz.flowlens")
                 && !Modifier.isAbstract(target.getModifiers()) && !target.isInterface()) {
                    return target.getName();
                }
            }
        } catch (Exception ignored) { /* class not found or ctx lookup failed */ }
        return dotForm;
    }

    /**
     * Returns {@code true} when {@code dotForm} represents a Spring-managed bean —
     * i.e. it is registered in the ApplicationContext OR carries a Spring stereotype
     * annotation (@Service, @Component, @Controller, @RestController, @Repository,
     * @Configuration).
     *
     * <p>Only beans are rendered as diagram participants.  Non-bean helper /
     * utility / builder classes are flattened transparently.
     */
    private boolean isApplicationBean(String dotForm) {
        try {
            Class<?> cls = Class.forName(dotForm, false,
                Thread.currentThread().getContextClassLoader());
            // 1. ApplicationContext lookup (most reliable at runtime)
            if (ctx != null) {
                try {
                    if (!ctx.getBeansOfType(cls, false, false).isEmpty()) return true;
                } catch (Exception ignored) {}
            }
            // 2. Annotation-based fallback (works even before ctx is queried fully)
            return hasSpringStereotype(cls, new HashSet<>());
        } catch (LinkageError | Exception ignored) {}
        return false;
    }

    /** Checks {@code cls} and its meta-annotations for Spring stereotype markers. */
    private static boolean hasSpringStereotype(Class<?> cls, Set<String> seen) {
        if (cls == null || !seen.add(cls.getName())) return false;
        for (java.lang.annotation.Annotation ann : cls.getAnnotations()) {
            String n = ann.annotationType().getName();
            if (n.equals("org.springframework.stereotype.Service")
             || n.equals("org.springframework.stereotype.Component")
             || n.equals("org.springframework.stereotype.Controller")
             || n.equals("org.springframework.web.bind.annotation.RestController")
             || n.equals("org.springframework.stereotype.Repository")) {
                return true;
            }
            // Walk meta-annotations so custom composed stereotypes are also detected
            if (hasSpringStereotype(ann.annotationType(), seen)) return true;
        }
        return false;
    }

    public static String simpleClassName(String fqn) {
        if (fqn == null) return "Unknown";
        String base = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
        return base.replaceAll("\\$\\$.*", "").replace('$', '.');
    }

    /**
     * Returns the display name for the primary database, resolved once from the
     * Spring {@link Environment}.  Inspects {@code spring.datasource.url},
     * {@code spring.datasource.driver-class-name}, {@code spring.r2dbc.url},
     * and common NoSQL properties to determine the actual database product.
     */
    private String getDatabaseName() {
        if (databaseDisplayName != null) return databaseDisplayName;
        synchronized (this) {
            if (databaseDisplayName == null) databaseDisplayName = detectDatabaseName();
        }
        return databaseDisplayName;
    }

    private String detectDatabaseName() {
        if (ctx == null) return "Database";
        try {
            Environment env = ctx.getEnvironment();

            // 1. JDBC datasource URL — most reliable indicator
            String url = env.getProperty("spring.datasource.url", "").toLowerCase();
            if (!url.isEmpty()) {
                if (url.contains("postgresql") || url.contains("postgres")) return "PostgreSQL";
                if (url.contains("mysql"))                                    return "MySQL";
                if (url.contains("mariadb"))                                  return "MariaDB";
                if (url.contains("oracle"))                                   return "Oracle DB";
                if (url.contains("sqlserver") || url.contains("mssql"))      return "SQL Server";
                if (url.contains(":h2:"))                                     return "H2";
                if (url.contains("sqlite"))                                   return "SQLite";
                if (url.contains(":db2:"))                                    return "DB2";
            }

            // 2. JDBC driver class name
            String driver = env.getProperty("spring.datasource.driver-class-name", "").toLowerCase();
            if (!driver.isEmpty()) {
                if (driver.contains("postgres"))   return "PostgreSQL";
                if (driver.contains("mysql"))      return "MySQL";
                if (driver.contains("mariadb"))    return "MariaDB";
                if (driver.contains("oracle"))     return "Oracle DB";
                if (driver.contains("sqlserver"))  return "SQL Server";
                if (driver.contains("h2"))         return "H2";
                if (driver.contains("db2"))        return "DB2";
            }

            // 3. R2DBC URL
            String r2dbc = env.getProperty("spring.r2dbc.url", "").toLowerCase();
            if (!r2dbc.isEmpty()) {
                if (r2dbc.contains("postgresql") || r2dbc.contains("postgres")) return "PostgreSQL";
                if (r2dbc.contains("mysql"))                                     return "MySQL";
                if (r2dbc.contains("mariadb"))                                   return "MariaDB";
                if (r2dbc.contains("mssql"))                                     return "SQL Server";
                if (r2dbc.contains("h2"))                                        return "H2";
            }

            // 4. Cassandra
            if (env.getProperty("spring.cassandra.contact-points") != null
             || env.getProperty("spring.data.cassandra.contact-points") != null) return "Cassandra";

        } catch (Exception ignored) { /* context not ready or property not available */ }
        return "Database";
    }

    // ── Inner record ───────────────────────────────────────────────────────────

    private record MethodCallInfo(String owner, String name, String descriptor, String ldcArg) {}
}
