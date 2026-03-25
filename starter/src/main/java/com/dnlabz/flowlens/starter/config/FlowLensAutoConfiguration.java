package com.dnlabz.flowlens.starter.config;

import com.dnlabz.flowlens.starter.analysis.MermaidGenerator;
import com.dnlabz.flowlens.starter.analysis.StaticCallAnalyzer;
import com.dnlabz.flowlens.starter.aspect.FlowLensAspect;
import com.dnlabz.flowlens.starter.aspect.FlowLensExecutorAspect;
import com.dnlabz.flowlens.starter.discovery.EndpointDiscovery;
import com.dnlabz.flowlens.starter.store.TraceStore;
import com.dnlabz.flowlens.starter.web.FlowLensController;
import com.dnlabz.flowlens.starter.web.FlowLensWebSocketHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.logging.Logger;

/**
 * Spring Boot auto-configuration for the FlowLens starter.
 *
 * <p>Activated automatically when the library is on the classpath of a
 * {@link ConditionalOnWebApplication servlet web application}.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableAspectJAutoProxy
@EnableWebSocket
public class FlowLensAutoConfiguration implements WebSocketConfigurer {

    private static final Logger LOG = Logger.getLogger(FlowLensAutoConfiguration.class.getName());

    // Injected via constructor so the WS handler bean is available at registration time.
    private final FlowLensWebSocketHandler wsHandler;

    public FlowLensAutoConfiguration(FlowLensWebSocketHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    // ── CGLIB safety ──────────────────────────────────────────────────────────

    /**
     * Automatically prevents {@code AopConfigException: No visible constructors}
     * for any third-party bean that uses a private constructor (singleton/registry
     * pattern). Must be {@code static} so it is instantiated before regular beans.
     */
    @Bean
    public static CglibSafetyPostProcessor flowLensCglibSafetyPostProcessor() {
        return new CglibSafetyPostProcessor();
    }

    // ── Core store ─────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public static TraceStore traceStore() {
        return new TraceStore();
    }

    // ── WebSocket ──────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public static FlowLensWebSocketHandler flowLensWebSocketHandler(ObjectMapper objectMapper) {
        return new FlowLensWebSocketHandler(objectMapper);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(wsHandler, "/flow-lens/ws").setAllowedOrigins("*");
    }

    // ── AOP aspects ────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public FlowLensAspect flowLensAspect(TraceStore traceStore) {
        LOG.info("[FlowLens] Auto-configured — intercepting API / Consumer / Scheduler entry points");
        return new FlowLensAspect(traceStore, wsHandler);
    }

    @Bean
    @ConditionalOnMissingBean
    public FlowLensExecutorAspect flowLensExecutorAspect() {
        return new FlowLensExecutorAspect();
    }

    // ── Endpoint discovery ────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public EndpointDiscovery endpointDiscovery() {
        return new EndpointDiscovery();
    }

    // ── Static analysis ────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public StaticCallAnalyzer staticCallAnalyzer() {
        return new StaticCallAnalyzer();
    }

    @Bean
    @ConditionalOnMissingBean
    public MermaidGenerator mermaidGenerator() {
        return new MermaidGenerator();
    }

    // ── REST controller ────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean
    public FlowLensController flowLensController(TraceStore traceStore,
                                                 EndpointDiscovery endpointDiscovery,
                                                 StaticCallAnalyzer staticCallAnalyzer,
                                                 MermaidGenerator mermaidGenerator) {
        return new FlowLensController(traceStore, wsHandler, endpointDiscovery,
                                      staticCallAnalyzer, mermaidGenerator);
    }

    // ── CORS ───────────────────────────────────────────────────────────────────

    @Bean
    public WebMvcConfigurer flowLensWebConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/flow-lens/**")
                    .allowedOrigins("*")
                    .allowedMethods("GET", "POST", "DELETE", "OPTIONS");
            }
            // NOTE: /flow-lens and /flow-lens/ routing is handled directly by
            // FlowLensController @GetMapping methods, which have higher priority
            // than addViewControllers (SimpleUrlHandlerMapping) and therefore
            // beat application wildcard routes such as @GetMapping("/{id}").
        };
    }
}
