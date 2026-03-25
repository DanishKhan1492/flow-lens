package com.dnlabz.flowlens.starter.discovery;

import com.dnlabz.flowlens.starter.model.DiscoveredEndpoint;
import com.dnlabz.flowlens.starter.model.EntryPointType;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.web.bind.annotation.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Scans all Spring beans at discovery time and returns metadata about every
 * detected API endpoint, message consumer, and scheduler method.
 *
 * <p>Results are used by the FlowLens dashboard to pre-populate the endpoint
 * list before any request has actually been made.
 */
public class EndpointDiscovery implements ApplicationContextAware {

    /** Methods inherited from java.lang.Object — never API endpoints. */
    private static final Set<String> OBJECT_METHODS = Set.of(
        "equals", "hashCode", "toString", "getClass",
        "wait", "notify", "notifyAll", "clone", "finalize"
    );

    private ApplicationContext ctx;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.ctx = applicationContext;
    }

    /** Scans all beans and returns a sorted list of discovered entry points. */
    public List<DiscoveredEndpoint> discover() {
        List<DiscoveredEndpoint> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String beanName : ctx.getBeanDefinitionNames()) {
            Object bean;
            try {
                bean = ctx.getBean(beanName);
            } catch (Exception ignored) {
                continue;
            }

            Class<?> targetClass = AopUtils.getTargetClass(bean);
            String pkg = targetClass.getPackageName();

            // Skip framework and FlowLens classes — only user application code
            if (pkg.startsWith("org.springframework")
             || pkg.startsWith("com.dnlabz.flowlens")
             || pkg.startsWith("com.fasterxml")
             || pkg.startsWith("java.")
             || pkg.startsWith("sun.")
             || pkg.startsWith("com.sun.")
             || pkg.startsWith("org.hibernate")
             || pkg.startsWith("org.apache")
             // Swagger / OpenAPI / SpringDoc infrastructure
             || pkg.startsWith("org.springdoc")
             || pkg.startsWith("springfox")
             || pkg.startsWith("io.swagger")) {
                continue;
            }

            for (Method method : targetClass.getMethods()) {
                // Skip Object methods and synthetic/bridge methods
                if (OBJECT_METHODS.contains(method.getName())) continue;
                if (method.isBridge() || method.isSynthetic()) continue;
                if (method.getDeclaringClass() == Object.class) continue;

                EntryPointType type = detectType(method, targetClass);
                if (type == null) continue;

                // Exclude Swagger UI, OpenAPI spec, Actuator, and Spring webjars endpoints
                if (type == EntryPointType.API) {
                    String path = httpPath(method, targetClass);
                    if (path != null && isInfrastructurePath(path)) continue;
                }

                String label = buildLabel(method, targetClass, type);
                String id    = targetClass.getName() + "#" + method.getName();

                if (seen.add(id)) {
                    // Simplify class name — strip CGLIB suffixes
                    String simpleName = targetClass.getSimpleName().replaceAll("\\$\\$.*", "");
                    String group      = resolveGroup(targetClass, type);
                    result.add(new DiscoveredEndpoint(id, type, label, simpleName, method.getName(), group));
                }
            }
        }

        // Sort: APIs first alphabetically, then consumers, then schedulers
        result.sort(Comparator
            .comparing(DiscoveredEndpoint::getType)
            .thenComparing(DiscoveredEndpoint::getLabel));
        return result;
    }

    // ── Entry-point type detection ─────────────────────────────────────────────

    private EntryPointType detectType(Method method, Class<?> targetClass) {
        // HTTP handler annotation on the method — the only reliable way to detect API endpoints.
        // We do NOT use the class-level @RestController fallback because that would match
        // every public method (including utility/helper methods) on the controller.
        if (isHttpMethod(method)) return EntryPointType.API;

        // Kafka
        if (hasAnnotation(method, "org.springframework.kafka.annotation.KafkaListener")
         || hasAnnotation(method, "org.springframework.kafka.annotation.KafkaListeners"))
            return EntryPointType.CONSUMER;

        // RabbitMQ
        if (hasAnnotation(method, "org.springframework.amqp.rabbit.annotation.RabbitListener")
         || hasAnnotation(method, "org.springframework.amqp.rabbit.annotation.RabbitListeners"))
            return EntryPointType.CONSUMER;

        // AWS SQS
        if (hasAnnotation(method, "io.awspring.cloud.sqs.annotation.SqsListener"))
            return EntryPointType.CONSUMER;

        // Scheduler
        if (hasAnnotation(method, "org.springframework.scheduling.annotation.Scheduled"))
            return EntryPointType.SCHEDULER;

        return null;
    }

    private static boolean isInfrastructurePath(String path) {
        String p = path.toLowerCase();
        return p.startsWith("/swagger")
            || p.startsWith("/v2/api-docs")
            || p.startsWith("/v3/api-docs")
            || p.startsWith("/api-docs")
            || p.startsWith("/webjars")
            || p.startsWith("/actuator")
            || p.startsWith("/error");
    }

    private boolean isHttpMethod(Method method) {
        return method.isAnnotationPresent(GetMapping.class)
            || method.isAnnotationPresent(PostMapping.class)
            || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(DeleteMapping.class)
            || method.isAnnotationPresent(PatchMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }

    private boolean hasAnnotation(Method method, String fqn) {
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> ann = (Class<? extends Annotation>) Class.forName(fqn);
            return method.isAnnotationPresent(ann);
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    // ── Label building (mirrors FlowLensAspect.buildLabel exactly) ─────────────

    private String buildLabel(Method method, Class<?> targetClass, EntryPointType type) {
        if (type == EntryPointType.API) {
            String httpVerb = httpVerb(method);
            String path     = httpPath(method, targetClass);
            if (path != null) return httpVerb + " " + path;
        }
        String simpleName = targetClass.getSimpleName().replaceAll("\\$\\$.*", "");
        return simpleName + "." + method.getName();
    }

    private String httpVerb(Method method) {
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
        String[] methodPaths = extractPaths(method);
        String classPath = "";
        RequestMapping classRm = targetClass.getAnnotation(RequestMapping.class);
        if (classRm == null && targetClass.getSuperclass() != null)
            classRm = targetClass.getSuperclass().getAnnotation(RequestMapping.class);
        if (classRm != null && classRm.value().length > 0) classPath = classRm.value()[0];

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

    // ── Group resolution ────────────────────────────────────────────────────────

    /**
     * Returns a human-readable group name for an endpoint.
     *
     * <ul>
     *   <li>If the class has an OpenAPI {@code @Tag} annotation, its {@code name} is used.</li>
     *   <li>Otherwise the simple class name is used (e.g. "UserController", "OrderConsumer").</li>
     * </ul>
     */
    private String resolveGroup(Class<?> targetClass, EntryPointType type) {
        // Try io.swagger.v3.oas.annotations.tags.Tag (SpringDoc / OpenAPI 3)
        try {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> tagAnn =
                (Class<? extends Annotation>) Class.forName("io.swagger.v3.oas.annotations.tags.Tag");
            Annotation tag = targetClass.getAnnotation(tagAnn);
            if (tag != null) {
                String name = (String) tagAnn.getMethod("name").invoke(tag);
                if (name != null && !name.isBlank()) return name;
            }
            // Also look at @Tags array
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> tagsAnn =
                (Class<? extends Annotation>) Class.forName("io.swagger.v3.oas.annotations.tags.Tags");
            Annotation tags = targetClass.getAnnotation(tagsAnn);
            if (tags != null) {
                Object[] tagArray = (Object[]) tagsAnn.getMethod("value").invoke(tags);
                if (tagArray != null && tagArray.length > 0) {
                    String name = (String) tagAnn.getMethod("name").invoke(tagArray[0]);
                    if (name != null && !name.isBlank()) return name;
                }
            }
        } catch (Exception ignored) { /* OpenAPI not on classpath or other issue */ }

        // Fallback: simple class name stripped of CGLIB suffixes
        return targetClass.getSimpleName().replaceAll("\\$\\$.*", "");
    }
}
