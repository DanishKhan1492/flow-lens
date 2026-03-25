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
            // ── Depth 0: decide whether to start a trace ─────────────────────
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

            boolean error = false;
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                error = true;
                throw t;
            } finally {
                stack.pop();
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
            // ── Depth > 0: nested call — attach as child of parent ────────────
            SpanBuilder current = new SpanBuilder(
                pjp.getTarget().getClass().getName(),
                pjp.getSignature().getName());
            // Add as child of the current top of the stack (the parent span)
            stack.peek().addChild(current);
            stack.push(current);

            boolean error = false;
            try {
                return pjp.proceed();
            } catch (Throwable t) {
                error = true;
                throw t;
            } finally {
                stack.pop();
                current.finish(error);
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

    // ── Inner helper: SpanBuilder ─────────────────────────────────────────────

    /**
     * Mutable builder accumulated by the advice; converted to an immutable
     * {@link TraceSpan} when the method returns.
     */
    static final class SpanBuilder {
        private final String className;
        private final String methodName;
        private final long   startNs;
        private long         endNs;
        private boolean      error;

        /** Pending children — added during execution via {@link #addChild}. */
        private final java.util.List<SpanBuilder> childBuilders = new java.util.ArrayList<>();

        SpanBuilder(String className, String methodName) {
            this.className  = className;
            this.methodName = methodName;
            this.startNs    = System.nanoTime();
        }

        void addChild(SpanBuilder child) {
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
