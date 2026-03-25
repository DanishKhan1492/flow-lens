package com.dnlabz.flowlens.starter.aspect;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

import java.util.concurrent.Callable;

/**
 * Intercepts task submission to {@link java.util.concurrent.Executor},
 * {@link java.util.concurrent.ExecutorService}, and
 * {@link java.util.concurrent.CompletableFuture} static helpers so that the
 * active FlowLens {@link FlowLensAspect.TraceContext} is propagated to worker
 * threads even when those threads were created before the trace started
 * (e.g. fixed thread pools, cached thread pools, Spring's {@code @Async}
 * executor, etc.).
 *
 * <h3>Why {@link InheritableThreadLocal} alone is not enough</h3>
 * <p>{@code InheritableThreadLocal} copies the value only when a
 * <em>new</em> thread is created.  Reusable thread-pool threads are created
 * once and reused for many tasks, so the inherited value is stale (or absent)
 * by the time a task runs.</p>
 *
 * <h3>What this aspect does</h3>
 * <p>At the moment a {@code Runnable} or {@code Callable} is <em>submitted</em>
 * to an executor the calling thread still holds the active trace context.  This
 * aspect wraps the task in a thin lambda that captures a snapshot of that
 * context, then sets/clears it on the worker thread around the real task body.
 * The worker thread therefore sees the correct parent span without any changes
 * to application code.</p>
 */
@Aspect
public class FlowLensExecutorAspect {

    // ─────────────────────────────────────────────────────────────────────────
    // Executor / ExecutorService submission methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wraps {@code Executor.execute(Runnable)} — covers all executor
     * implementations including {@code ThreadPoolExecutor},
     * {@code ForkJoinPool}, Spring's {@code TaskExecutor}, etc.
     */
    @Around("execution(void java.util.concurrent.Executor.execute(Runnable)) && args(task)")
    public Object wrapExecute(ProceedingJoinPoint pjp, Runnable task) throws Throwable {
        return pjp.proceed(new Object[]{ wrap(task) });
    }

    /**
     * Wraps {@code ExecutorService.submit(Runnable)} and
     * {@code ExecutorService.submit(Callable)}.
     */
    @Around("execution(* java.util.concurrent.ExecutorService.submit(..)) && args(task)")
    public Object wrapSubmit(ProceedingJoinPoint pjp, Object task) throws Throwable {
        Object wrapped = task instanceof Callable<?>  ? wrap((Callable<?>) task)
                       : task instanceof Runnable      ? wrap((Runnable)   task)
                       : task;
        return pjp.proceed(new Object[]{ wrapped });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CompletableFuture async factory methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Wraps {@code CompletableFuture.supplyAsync(Supplier)} and its
     * two-argument form {@code supplyAsync(Supplier, Executor)}.
     */
    @Around("execution(* java.util.concurrent.CompletableFuture.supplyAsync(..))")
    public Object wrapSupplyAsync(ProceedingJoinPoint pjp) throws Throwable {
        return pjp.proceed(wrapFirstArg(pjp.getArgs(), SupplierWrapper::new));
    }

    /**
     * Wraps {@code CompletableFuture.runAsync(Runnable)} and its
     * two-argument form {@code runAsync(Runnable, Executor)}.
     */
    @Around("execution(* java.util.concurrent.CompletableFuture.runAsync(..))")
    public Object wrapRunAsync(ProceedingJoinPoint pjp) throws Throwable {
        Object[] args = pjp.getArgs();
        if (args.length > 0 && args[0] instanceof Runnable r) {
            args[0] = wrap(r);
        }
        return pjp.proceed(args);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private static Object[] wrapFirstArg(Object[] args, java.util.function.Function<java.util.function.Supplier<Object>, java.util.function.Supplier<Object>> factory) {
        if (args.length > 0 && args[0] instanceof java.util.function.Supplier) {
            @SuppressWarnings("unchecked")
            java.util.function.Supplier<Object> original = (java.util.function.Supplier<Object>) args[0];
            args[0] = factory.apply(original);
        }
        return args;
    }

    /** Wraps a {@link Runnable} to propagate the current trace context. */
    static Runnable wrap(Runnable task) {
        FlowLensAspect.TraceContext ctx = FlowLensAspect.propagationLocal.get();
        if (ctx == null || ctx.isFinished()) return task;
        FlowLensAspect.SpanBuilder capturedSpan = ctx.captureCurrentSpan();
        return () -> {
            FlowLensAspect.propagationLocal.set(ctx);
            ctx.setCurrentSpan(capturedSpan);
            try {
                task.run();
            } finally {
                FlowLensAspect.propagationLocal.remove();
            }
        };
    }

    /** Wraps a {@link Callable} to propagate the current trace context. */
    static <V> Callable<V> wrap(Callable<V> task) {
        FlowLensAspect.TraceContext ctx = FlowLensAspect.propagationLocal.get();
        if (ctx == null || ctx.isFinished()) return task;
        FlowLensAspect.SpanBuilder capturedSpan = ctx.captureCurrentSpan();
        return () -> {
            FlowLensAspect.propagationLocal.set(ctx);
            ctx.setCurrentSpan(capturedSpan);
            try {
                return task.call();
            } finally {
                FlowLensAspect.propagationLocal.remove();
            }
        };
    }

    /** Wraps a {@link java.util.function.Supplier} to propagate the current trace context. */
    private static final class SupplierWrapper<T> implements java.util.function.Supplier<T> {
        private final java.util.function.Supplier<T> delegate;
        private final FlowLensAspect.TraceContext ctx;
        private final FlowLensAspect.SpanBuilder capturedSpan;

        SupplierWrapper(java.util.function.Supplier<T> delegate) {
            this.delegate      = delegate;
            this.ctx           = FlowLensAspect.propagationLocal.get();
            this.capturedSpan  = (ctx != null) ? ctx.captureCurrentSpan() : null;
        }

        @Override
        public T get() {
            if (ctx == null || ctx.isFinished()) return delegate.get();
            FlowLensAspect.propagationLocal.set(ctx);
            ctx.setCurrentSpan(capturedSpan);
            try {
                return delegate.get();
            } finally {
                FlowLensAspect.propagationLocal.remove();
            }
        }
    }
}
