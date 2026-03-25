package com.dnlabz.flowlens.starter.web;

import com.dnlabz.flowlens.starter.analysis.MermaidGenerator;
import com.dnlabz.flowlens.starter.analysis.StaticCallAnalyzer;
import com.dnlabz.flowlens.starter.discovery.EndpointDiscovery;
import com.dnlabz.flowlens.starter.model.DiscoveredEndpoint;
import com.dnlabz.flowlens.starter.model.TraceRecord;
import com.dnlabz.flowlens.starter.store.TraceStore;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FlowLens dashboard + API controller.
 *
 * <p>Uses {@code @Controller} (not {@code @RestController}) so that the UI
 * routing methods can return redirect/forward view names while the API methods
 * use {@code @ResponseBody}.</p>
 *
 * <p>The dashboard routes ({@code /flow-lens} and {@code /flow-lens/}) are
 * registered here as explicit {@code @GetMapping}s on
 * {@code RequestMappingHandlerMapping} (order 0). This ensures they take
 * priority over any application-level wildcard routes such as
 * {@code @GetMapping("/{id}")} that would otherwise steal the request and
 * produce a {@code NumberFormatException}.</p>
 */
@Controller
public class FlowLensController {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    private final TraceStore traceStore;
    private final FlowLensWebSocketHandler wsHandler;
    private final EndpointDiscovery endpointDiscovery;
    private final StaticCallAnalyzer staticCallAnalyzer;
    private final MermaidGenerator mermaidGenerator;

    public FlowLensController(TraceStore traceStore, FlowLensWebSocketHandler wsHandler,
                              EndpointDiscovery endpointDiscovery,
                              StaticCallAnalyzer staticCallAnalyzer,
                              MermaidGenerator mermaidGenerator) {
        this.traceStore         = traceStore;
        this.wsHandler          = wsHandler;
        this.endpointDiscovery  = endpointDiscovery;
        this.staticCallAnalyzer = staticCallAnalyzer;
        this.mermaidGenerator   = mermaidGenerator;
    }

    // ── Dashboard UI routing ──────────────────────────────────────────────────

    /**
     * {@code GET /flow-lens} → redirect to {@code /flow-lens/}.
     * Registered as a real MVC mapping so it beats wildcard /{id} routes.
     * The redirect URL is built from the incoming request so it preserves any
     * context path automatically.
     */
    @GetMapping("/flow-lens")
    public String dashboardRedirect() {
        // Spring's redirect: view name automatically prepends the context path,
        // so this works whether server.servlet.context-path is set or not.
        return "redirect:/flow-lens/";
    }

    /**
     * {@code GET /flow-lens/} → forward to the embedded {@code index.html}.
     * Spring Boot serves META-INF/resources/flow-lens/index.html as a static
     * resource under the path {@code /flow-lens/index.html}.
     */
    @GetMapping("/flow-lens/")
    public String dashboardIndex() {
        return "forward:/flow-lens/index.html";
    }

    // ── REST API ──────────────────────────────────────────────────────────────

    /** Returns all stored traces, newest first. */
    @GetMapping("/flow-lens/api/traces")
    @ResponseBody
    public List<TraceRecord> getTraces() {
        return traceStore.getAll();
    }

    /** Returns a single trace by its ID. */
    @GetMapping("/flow-lens/api/traces/{id}")
    @ResponseBody
    public ResponseEntity<TraceRecord> getTrace(@PathVariable String id) {
        TraceRecord record = traceStore.getById(id);
        return record != null
            ? ResponseEntity.ok(record)
            : ResponseEntity.notFound().build();
    }

    /** Clears all stored traces and notifies WebSocket clients. */
    @DeleteMapping("/flow-lens/api/traces")
    @ResponseBody
    public ResponseEntity<Void> clearTraces() {
        traceStore.clear();
        wsHandler.broadcastClear();
        return ResponseEntity.noContent().build();
    }

    /** Health / ping endpoint for the dashboard connection check. */
    @GetMapping("/flow-lens/api/ping")
    @ResponseBody
    public String ping() {
        return "ok";
    }

    /** Returns all discovered entry points (pre-populated before any execution). */
    @GetMapping("/flow-lens/api/endpoints")
    @ResponseBody
    public List<DiscoveredEndpoint> getEndpoints() {
        return endpointDiscovery.discoverEndpoints();
    }

    /**
     * Performs static bytecode analysis on the given entry-point and returns the
     * Mermaid {@code sequenceDiagram} definition so the dashboard can render it
     * without the API ever being triggered.
     *
     * @param id  the endpoint id: "fully.qualified.ClassName#methodName"
     */
    @GetMapping("/flow-lens/api/diagram")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getDiagram(@RequestParam String id) {
        int sep = id.lastIndexOf('#');
        if (sep < 0 || sep == id.length() - 1) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "id must be in the format ClassName#methodName"));
        }
        String fqClass    = id.substring(0, sep);
        String methodName = id.substring(sep + 1);

        // Find the matching discovered endpoint for label / type metadata
        DiscoveredEndpoint ep = endpointDiscovery.discoverEndpoints().stream()
            .filter(e -> e.getId().equals(id))
            .findFirst()
            .orElseGet(() -> new DiscoveredEndpoint(
                id, null, methodName,
                StaticCallAnalyzer.simpleClassName(fqClass), methodName,
                StaticCallAnalyzer.simpleClassName(fqClass)));

        com.dnlabz.flowlens.starter.analysis.CallNode tree =
            staticCallAnalyzer.analyze(fqClass, methodName);
        String diagram = mermaidGenerator.generate(ep, tree);

        Map<String, Object> result = new HashMap<>();
        result.put("diagram", diagram);
        result.put("label",   ep.getLabel());
        result.put("type",    ep.getType());
        return ResponseEntity.ok(result);
    }

    // ── HTTP proxy for the dashboard request panel ────────────────────────────

    /**
     * Server-side HTTP proxy used by the embedded dashboard's request panel.
     * Forwards arbitrary HTTP requests from the browser, avoiding CORS restrictions.
     *
     * <p>Only {@code http://} and {@code https://} target URLs are accepted.
     */
    @PostMapping("/flow-lens/api/proxy")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> proxy(@RequestBody ProxyRequest req)
            throws IOException, InterruptedException {

        String targetUrl = req.url() == null ? "" : req.url().trim();
        if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Only http:// and https:// URLs are supported"));
        }

        String method = req.method() == null ? "GET" : req.method().toUpperCase();

        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(targetUrl))
            .timeout(Duration.ofSeconds(30));

        if (req.headers() != null) {
            req.headers().forEach((k, v) -> {
                // Skip headers that HttpClient manages itself
                if (!k.equalsIgnoreCase("host") && !k.equalsIgnoreCase("content-length")) {
                    builder.header(k, v);
                }
            });
        }

        String body = req.body() == null ? "" : req.body();
        boolean hasBody = !List.of("GET", "HEAD", "DELETE").contains(method) && !body.isBlank();
        builder.method(method, hasBody
            ? HttpRequest.BodyPublishers.ofString(body)
            : HttpRequest.BodyPublishers.noBody());

        long start = System.currentTimeMillis();
        HttpResponse<String> resp = HTTP_CLIENT.send(
            builder.build(), HttpResponse.BodyHandlers.ofString());
        long durationMs = System.currentTimeMillis() - start;

        Map<String, String> responseHeaders = new HashMap<>();
        resp.headers().map().forEach((k, vs) -> responseHeaders.put(k, String.join(", ", vs)));

        Map<String, Object> result = new HashMap<>();
        result.put("status",     resp.statusCode());
        result.put("statusText", "");
        result.put("headers",    responseHeaders);
        result.put("body",       resp.body());
        result.put("durationMs", durationMs);
        return ResponseEntity.ok(result);
    }

    /** DTO for proxy requests coming from the dashboard. */
    public record ProxyRequest(
        String method,
        String url,
        Map<String, String> headers,
        String body
    ) {}
}
