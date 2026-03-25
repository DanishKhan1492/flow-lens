package com.dnlabz.flowlens.starter.aspect;

import com.dnlabz.flowlens.starter.model.EntryPointType;
import com.dnlabz.flowlens.starter.model.TraceRecord;
import com.dnlabz.flowlens.starter.model.TraceSpan;
import com.dnlabz.flowlens.starter.store.TraceStore;
import com.dnlabz.flowlens.starter.web.FlowLensWebSocketHandler;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Logger;

/**
 * Core Spring AOP aspect that intercepts every public cross-bean method call
 * and builds a {@link TraceRecord} tree for each request entry point.
 *
 * <p><b>How it works:</b>
 * <ol>
 *   <li>When the call stack is empty (depth 0) we inspect the target method's
 *       annotations.  If it is an HTTP handler, consumer, or scheduler we start
 *       a new trace; otherwise the call passes through untraced.</li>
 *   <li>At depth &gt; 0 we push a {@link SpanBuilder} onto the thread-local
 *       stack, nest it under the current parent, and pop it on exit.</li>
 *   <li>When depth returns to 0 the completed tree is saved to
 *       {@link TraceStore} and broadcast over WebSocket.</li>
 * </ol>
 *
 * <p><b>Scope limitation:</b> Spring proxy-based AOP only intercepts cross-bean
 * calls.  Internal method calls within the same class are NOT intercepted, which
 * is fine for typical Controller → Service → Repository chains.
 */
@Aspect
public class FlowLensAspect {

    private static final Logger LOG = Logger.getLogger(FlowLensAspect.class.getName());

    // ── Thread-local state ────────────────────────────────────────────────────

    /** Stack of in-progress spans on this thread.  Empty = not in a trace. */
    private final ThreadLocal<Deque<SpanBuilder>> stackLocal =
        ThreadLocal.withInitial(ArrayDeque::new);

    /** The entry-point type (API / CONSUMER / SCHEDULER) for the active trace. */
    private final ThreadLocal<EntryPointType> typeLocal = new ThreadLocal<>();

    /** Human-readable label for the active trace entry point. */
    private final ThreadLocal<String> labelLocal = new ThreadLocal<>();

    /**
     * Cross-thread propagation context.
     *
     * <p>When a trace is active on a thread, this holds the {@link TraceContext}
     * for that trace.  Because it is an {@link InheritableThreadLocal}, any new
     * thread created while a trace is active (e.g. via {@code @Async}, a thread
     * pool, or an explicit {@code new Thread(...)}) automatically inherits a
     * reference to the same context.  The child thread can then attach its spans
     * as children of whatever span was current at the moment of hand-off, giving
     * a complete cross-thread call tree without any instrumentation of executors.
     *
     * <p>For reusable thread pools (where inheritance does not apply),
     * {@link FlowLensExecutorAspect} intercepts task submission and explicitly
     * sets this value on the worker thread before the task runs.
     */
    static final InheritableThreadLocal<TraceContext> propagationLocal =
        new InheritableThreadLocal<>();

    // ── Dependencies ──────────────────────────────────────────────────────────

    private final TraceStore traceStore;
    private final FlowLensWebSocketHandler wsHandler;

    public FlowLensAspect(TraceStore traceStore, FlowLensWebSocketHandler wsHandler) {
        this.traceStore = traceStore;
        this.wsHandler  = wsHandler;
    }

    // ── Advice ────────────────────────────────────────────────────────────────

    /**
     * Intercepts public methods on beans annotated with Spring stereotype annotations.
     * Using @within with explicit stereotypes (instead of package exclusions) naturally
     * limits interception to user application code only — third-party beans, final classes,
     * and infrastructure objects are never matched regardless of their package.
     */
    @Around(
        "execution(public * *(..)) && " +
        "!within(com.dnlabz.flowlens.starter..*) && " +
        "(" +
        "  @within(org.springframework.stereotype.Component)          || " +
        "  @within(org.springframework.stereotype.Service)            || " +
        "  @within(org.springframework.stereotype.Repository)         || " +
        "  @within(org.springframework.stereotype.Controller)         || " +
        "  @within(org.springframework.web.bind.annotation.RestController)" +
        ")"
    )
    public Object trace(ProceedingJoinPoint pjp) throws Throwable {
        Deque<SpanBuilder> stack = stackLocal.get();

        if (stack.isEmpty()) {
            // ── No active trace on this thread ────────────────────────────────

            // Check if a parent trace was propagated from another thread
            // (e.g. via @Async, CompletableFuture, a thread pool task, etc.)
            TraceContext inherited = propagationLocal.get();
            if (inherited != null && !inherited.isFinished()) {
                // Attach this whole sub-call as an async child of the parent span
                SpanBuilder asyncChild = new SpanBuilder(
                    pjp.getTarget().getClass().getName(),
                    pjp.getSignature().getName());
                inherited.attachChild(asyncChild);
                stack.push(asyncChild);

                boolean error = false;
                try {
                    return pjp.proceed();
                } catch (Throwable t) {
                    error = true;
                    throw t;
                } finally {
                    stack.pop();
                    asyncChild.finish(error);
                    // Clear so this thread does not re-attach on subsequent calls
                    // that are not triggered by the same parent
                    if (stack.isEmpty()) {
                        propagationLocal.remove();
                    }
                }
            }

            // ── Depth 0: decide whether to start a brand-new trace ────────────
            EntryPointType type = detectEntryPoint(pjp);
            if (type == null) {
                // Not a recognised entry point — execute without tracing
                return pjp.proceed();
            }

            String label = buildLabel(pjp, type);
            typeLocal.set(type);
            labelLocal.set(label);

            SpanBuilder root = new SpanBuilder(
                pjp.getTarget().getClass().getName(),
                pjp.getSignature().getName());
            stack.push(root);

            // Publish the trace context so child threads can attach their spans
            TraceContext ctx = new TraceContext(root);
            propagationLocal.set(ctx);

            boolean error = false;
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                error = true;
                throw t;
            } finally {
                stack.pop();
                ctx.markFinished();          // child threads must not attach after this
                propagationLocal.remove();
                if (stack.isEmpty()) {
                    // Trace complete — build and publish the record
                    TraceSpan rootSpan = root.build(error);
                    TraceRecord record = new TraceRecord(
                        typeLocal.get(),
                        labelLocal.get(),
                        root.durationMs(),
                        error,
                        rootSpan);
                    typeLocal.remove();
                    labelLocal.remove();
                    traceStore.add(record);
                    wsHandler.broadcastTrace(record);
                }
            }

        } else {
            // ── Depth > 0: nested call on the same thread ─────────────────────
            SpanBuilder current = new SpanBuilder(
                pjp.getTarget().getClass().getName(),
                pjp.getSignature().getName());
            // Add as child of the current top of the stack (the parent span)
            stack.peek().addChild(current);
            stack.push(current);

            // Keep the propagation context pointing at the innermost span so
            // that if this call submits async work the new thread attaches under
            // the correct parent (the span that spawned it, not the root).
            TraceContext ctx = propagationLocal.get();
            if (ctx != null) ctx.setCurrentSpan(current);

            boolean error = false;
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                error = true;
                throw t;
            } finally {
                stack.pop();
                current.finish(error);
                // Restore parent as current after this span finishes
                if (ctx != null) {
                    SpanBuilder parent = stack.isEmpty() ? null : stack.peek();
                    if (parent != null) ctx.setCurrentSpan(parent);
                }
            }
        }
    }

    // ── Entry point detection ─────────────────────────────────────────────────

    /**
     * Returns the {@link EntryPointType} if the intercepted method is a
     * recognised entry point, or {@code null} if it should not be traced.
     */
    private EntryPointType detectEntryPoint(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Class<?> targetClass = pjp.getTarget().getClass();

        // ── API: HTTP handler annotations on the method ───────────────────────
        if (isHttpHandlerMethod(method)) return EntryPointType.API;

        // ── API: class-level @RestController / @Controller ───────────────────
        if (hasAnnotation(targetClass, "org.springframework.web.bind.annotation.RestController")
         || hasAnnotation(targetClass, "org.springframework.stereotype.Controller")) {
            return EntryPointType.API;
        }

        // ── CONSUMER: @KafkaListener ─────────────────────────────────────────
        if (hasAnnotation(method, "org.springframework.kafka.annotation.KafkaListener")
         || hasAnnotation(method, "org.springframework.kafka.annotation.KafkaListeners")) {
            return EntryPointType.CONSUMER;
        }

        // ── CONSUMER: @RabbitListener ─────────────────────────────────────────
        if (hasAnnotation(method, "org.springframework.amqp.rabbit.annotation.RabbitListener")
         || hasAnnotation(method, "org.springframework.amqp.rabbit.annotation.RabbitListeners")) {
            return EntryPointType.CONSUMER;
        }

        // ── CONSUMER: @SqsListener (AWS) ─────────────────────────────────────
        if (hasAnnotation(method, "io.awspring.cloud.sqs.annotation.SqsListener")) {
            return EntryPointType.CONSUMER;
        }

        // ── SCHEDULER: @Scheduled ─────────────────────────────────────────────
        if (hasAnnotation(method, "org.springframework.scheduling.annotation.Scheduled")) {
            return EntryPointType.SCHEDULER;
        }

        return null;
    }

    private boolean isHttpHandlerMethod(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class)
            || method.isAnnotationPresent(PatchMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }

    /**
     * Checks for an annotation by fully-qualified name using reflection so we
     * do not fail if optional dependencies (Kafka, Rabbit, etc.) are absent.
     */
    private boolean hasAnnotation(Method method, String annotationFqn) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> ann =
                (Class<? extends Annotation>) Class.forName(annotationFqn);
            return method.isAnnotationPresent(ann);
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private boolean hasAnnotation(Class<?> clazz, String annotationFqn) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> ann =
                (Class<? extends Annotation>) Class.forName(annotationFqn);
            return clazz.isAnnotationPresent(ann);
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    // ── Label building ────────────────────────────────────────────────────────

    private String buildLabel(ProceedingJoinPoint pjp, EntryPointType type) {
        if (type == EntryPointType.API) {
            Method method = ((MethodSignature) pjp.getSignature()).getMethod();
            String httpMethod = httpMethodName(method);
            String path       = httpPath(method, pjp.getTarget().getClass());
            if (path != null) return httpMethod + " " + path;
        }
        // Fallback: ClassName.method
        String simpleName = pjp.getTarget().getClass().getSimpleName();
        if (simpleName.contains("$$")) simpleName = simpleName.split("\\$\\$")[0];
        return simpleName + "." + pjp.getSignature().getName();
    }

    private String httpMethodName(Method method) {
        if (method.isAnnotationPresent(GetMapping.class))    return "GET";
        if (method.isAnnotationPresent(PostMapping.class))   return "POST";
        if (method.isAnnotationPresent(PutMapping.class))    return "PUT";
        if (method.isAnnotationPresent(DeleteMapping.class)) return "DELETE";
        if (method.isAnnotationPresent(PatchMapping.class))  return "PATCH";
        if (method.isAnnotationPresent(RequestMapping.class)) {
            RequestMapping rm = method.getAnnotation(RequestMapping.class);
            if (rm.method().length > 0) return rm.method()[0].name();
        }
        return "HTTP";
    }

    private String httpPath(Method method, Class<?> targetClass) {
        // Method-level path
        String[] methodPaths = extractPaths(method);
        // Class-level @RequestMapping prefix
        String[] classPaths  = extractClassPaths(targetClass);

        String classPath  = (classPaths  != null && classPaths.length  > 0) ? classPaths[0]  : "";
        String methodPath = (methodPaths != null && methodPaths.length > 0) ? methodPaths[0] : "";

        String full = (classPath + "/" + methodPath).replaceAll("//+", "/");
        return full.isEmpty() ? null : full;
    }

    private String[] extractPaths(Method method) {
        if (method.isAnnotationPresent(GetMapping.class))
            return method.getAnnotation(GetMapping.class).value();
        if (method.isAnnotationPresent(PostMapping.class))
            return method.getAnnotation(PostMapping.class).value();
        if (method.isAnnotationPresent(PutMapping.class))
            return method.getAnnotation(PutMapping.class).value();
        if (method.isAnnotationPresent(DeleteMapping.class))
            return method.getAnnotation(DeleteMapping.class).value();
        if (method.isAnnotationPresent(PatchMapping.class))
            return method.getAnnotation(PatchMapping.class).value();
        if (method.isAnnotationPresent(RequestMapping.class))
            return method.getAnnotation(RequestMapping.class).value();
        return new String[0];
    }

    private String[] extractClassPaths(Class<?> clazz) {
        RequestMapping rm = clazz.getAnnotation(RequestMapping.class);
        if (rm != null) return rm.value();
        // Also check the real target class (CGLIB proxies delegate annotation lookup)
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null) {
            rm = superclass.getAnnotation(RequestMapping.class);
            if (rm != null) return rm.value();
        }
        return new String[0];
    }

    // ── Inner helper: TraceContext ────────────────────────────────────────────

    /**
     * Shared mutable context for a single trace that may span multiple threads.
     *
     * <p>Holds a reference to whichever {@link SpanBuilder} is "current" on
     * the originating thread; child threads inherit this reference via
     * {@link InheritableThreadLocal} and attach their spans to it.
     *
     * <p>All mutations are guarded by the instance monitor to make concurrent
     * child-thread {@code attachChild} calls safe.</p>
     */
    static final class TraceContext {
        private volatile SpanBuilder currentSpan;
        private volatile boolean finished = false;

        TraceContext(SpanBuilder root) {
            this.currentSpan = root;
        }

        synchronized void attachChild(SpanBuilder child) {
            if (!finished && currentSpan != null) {
                currentSpan.addChild(child);
            }
        }

        synchronized void setCurrentSpan(SpanBuilder span) {
            if (!finished) this.currentSpan = span;
        }

        /** Returns a snapshot of the current span for use by executor wrappers. */
        synchronized SpanBuilder captureCurrentSpan() {
            return currentSpan;
        }

        void markFinished() {
            this.finished = true;
        }

        boolean isFinished() {
            return finished;
        }
    }

    // ── Inner helper: SpanBuilder ─────────────────────────────────────────────

    /**
     * Mutable builder accumulated by the advice; converted to an immutable
     * {@link TraceSpan} when the method returns.
     *
     * <p>{@code addChild} is {@code synchronized} because cross-thread spans
     * may be added concurrently from multiple child threads.</p>
     */
    static final class SpanBuilder {
        private final String className;
        private final String methodName;
        private final long   startNs;
        private long         endNs;
        private boolean      error;

        /** Pending children — added during execution via {@link #addChild}. */
        private final java.util.List<SpanBuilder> childBuilders =
            new java.util.concurrent.CopyOnWriteArrayList<>();

        SpanBuilder(String className, String methodName) {
            this.className  = className;
            this.methodName = methodName;
            this.startNs    = System.nanoTime();
        }

        synchronized void addChild(SpanBuilder child) {
            childBuilders.add(child);
        }

        /** Called at method exit — records end time and error flag. */
        void finish(boolean error) {
            this.endNs = System.nanoTime();
            this.error = error;
        }

        /** Duration in ms for the root span (entry-point call). */
        long durationMs() {
            return (endNs - startNs) / 1_000_000L;
        }

        /**
         * Recursively converts this builder (and all children) into an
         * immutable {@link TraceSpan} tree.
         */
        TraceSpan build(boolean overrideError) {
            finish(overrideError);
            TraceSpan span = new TraceSpan(
                className, methodName,
                (endNs - startNs) / 1_000_000L,
                error);
            for (SpanBuilder child : childBuilders) {
                span.addChild(child.build(child.error));
            }
            return span;
        }
    }
}
