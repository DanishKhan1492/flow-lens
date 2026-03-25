# FlowLens

**FlowLens** is a zero-configuration Spring Boot starter that automatically generates interactive sequence diagrams for every entry point in your application — REST endpoints, Kafka/RabbitMQ consumers, and scheduled tasks — using static bytecode analysis. No code changes, annotations, or runtime instrumentation required.

![FlowLens Dashboard](https://github.com/user-attachments/assets/placeholder)

---

## How It Works

When your application starts, FlowLens:

1. **Scans all Spring beans** via `ApplicationContext` and discovers every `@RestController`, `@KafkaListener`, `@RabbitListener`, `@SqsListener`, and `@Scheduled` method.
2. On demand (when you click an endpoint in the dashboard), performs **static bytecode analysis** (using Spring's embedded ASM) on that method and traces every call it makes through your services, repositories, and external clients.
3. Renders the resulting call tree as a **Mermaid sequence diagram** inside the embedded Next.js dashboard, served directly from your running application at `/flow-lens/`.

No agent, no proxy, no log parsing — just pure static analysis of your application's own bytecode.

---

## Requirements

| Requirement | Version |
|---|---|
| Java | **17+** |
| Spring Boot | **2.7.x, 3.x, or 4.x** |
| Spring Web (MVC) | required (servlet stack) |
| Spring WebSocket | required (included transitively) |

> FlowLens only works with the **servlet stack** (`spring-boot-starter-web`). Reactive / WebFlux applications are not supported.
>
> **Spring Boot 2.6 and below are not supported.** The `@AutoConfiguration` annotation and the auto-configuration imports file (`META-INF/spring/...`) were both introduced in Spring Boot 2.7.

---

## Installation

### 1. Add the dependency

**Maven (`pom.xml`):**
```xml
<dependency>
    <groupId>com.dnlabz.flowlens</groupId>
    <artifactId>flowlens-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle (`build.gradle.kts`):**
```kotlin
implementation("com.dnlabz.flowlens:flowlens-spring-boot-starter:1.0.0")
```

**Gradle (`build.gradle`):**
```groovy
implementation 'com.dnlabz.flowlens:flowlens-spring-boot-starter:1.0.0'
```

### 2. Publish to your local Maven repo (if building from source)

```bash
git clone https://github.com/your-org/flow-lens.git
cd flow-lens
./gradlew :starter:publishToMavenLocal
```

Then add `mavenLocal()` to your project's repositories block:

```kotlin
// settings.gradle.kts or build.gradle.kts
repositories {
    mavenLocal()
    mavenCentral()
}
```

### 3. That's it

No `@EnableFlowLens`, no properties, no extra beans. Spring Boot's auto-configuration kicks in automatically. Start your application and open:

```
http://localhost:8080/flow-lens/
```

---

## Dashboard

The FlowLens dashboard is embedded inside the JAR and served as static resources at the `/flow-lens/` path. It has three panels:

| Panel | Description |
|---|---|
| **Endpoint list** (left sidebar) | All discovered entry points, grouped by controller/class. Groups are **collapsed by default** — click a group header to expand it. Filter by type (API / Consumer / Scheduler) using the tabs at the top. |
| **Sequence diagram** (main area) | Auto-generated Mermaid sequence diagram for the selected endpoint |
| **Toolbar** | Zoom in/out, drag-to-pan, Copy Mermaid code, Step Flow mode |

### Step Flow mode

Click **▶ Step Flow** to walk through the diagram one call at a time. Use the **Prev / Next** buttons, keyboard arrow keys, or hit **Play** to auto-advance. Speed can be set to 0.5×, 1×, or 2×.

---

## What Gets Discovered

FlowLens scans for the following entry point types at startup:

### API — HTTP Endpoints
Methods on beans annotated with `@RestController` or `@Controller` that carry HTTP mapping annotations:
- `@GetMapping`, `@PostMapping`, `@PutMapping`, `@PatchMapping`, `@DeleteMapping`, `@RequestMapping`

The following paths are automatically excluded (Swagger UI, OpenAPI spec, Spring Actuator):
- `/swagger-ui/**`, `/v2/api-docs`, `/v3/api-docs`, `/api-docs/**`
- `/webjars/**`
- `/actuator/**`, `/error`

Beans from `org.springdoc`, `springfox`, and `io.swagger` packages are also skipped entirely.

### Consumer — Message Listeners
Methods annotated with any of:
- `@KafkaListener` (Spring Kafka)
- `@RabbitListener` (Spring AMQP)
- `@SqsListener` (Spring Cloud AWS)
- `@JmsListener` (Spring JMS)

### Scheduler — Scheduled Tasks
Methods annotated with `@Scheduled`.

---

## What Gets Shown in the Diagram

The sequence diagram shows calls between **Spring-managed beans** only. FlowLens automatically filters out:

- Framework internals (`org.springframework.*`, `org.hibernate.*`, `reactor.*`, `io.netty.*`, etc.)
- JDK classes (`java.*`, `javax.*`, `jakarta.*`, `sun.*`)
- Data/DTO classes (any class in `.entity.`, `.dto.`, `.model.`, `.vo.` packages, or with those suffixes)
- Utility/mapper/config classes
- Third-party libraries (Jackson, BouncyCastle, gRPC stubs, etc.)
- Participants that were collected but have no arrows pointing to them (dead participants are pruned automatically)

### External system participants

Boundary systems are shown as **named participants** — FlowLens tries to extract the most specific name available via static bytecode analysis:

| System | Participant label | How detected |
|---|---|---|
| **Database** | `PostgreSQL`, `MySQL`, `H2`, … | Reads `spring.datasource.url` / `spring.datasource.driver-class-name` / `spring.r2dbc.url` at startup; falls back to `Database` |
| **REST / HTTP** | `payments.acme.com`, `payment-service`, … | URL string literal → host extracted; `@FeignClient(name=…)` or `url=…` attribute; `@Value("${prop}")` field resolved from `application.properties` |
| **Kafka** | `topic:order-events`, … | Topic string literal passed to `KafkaTemplate.send()` |
| **Messaging (AMQP/SQS/SNS)** | `queue:order-queue`, … | Queue/exchange name string literal |
| **Cadence / Temporal** | `OrderWorkflow [Cadence]`, … | Workflow name string passed to stub/client |
| **Redis** | `Redis` | `RedisTemplate`, Lettuce, Jedis, Redisson |
| **Elasticsearch** | `Elasticsearch` | `ElasticsearchOperations`, Elastic Java client |
| **MongoDB** | `MongoDB` | `MongoTemplate` |
| **gRPC** | gRPC stub class name | gRPC stub suffix detection |

#### REST service name resolution (in priority order)

1. Absolute URL literal in the call: `restTemplate.getForEntity("https://payments.acme.com/v1/pay", ...)` → `payments.acme.com`
2. `@FeignClient(url = "https://...")` → host extracted from the URL
3. `@FeignClient(name = "payment-service")` or `value = "…"` → service name used as-is
4. `@Value("${payment.service.url}") String baseUrl` field → property resolved at startup, host extracted
5. `baseUrl + "/path"` (Java string concatenation) → host from `baseUrl` preserved through concat
6. Fallback: `External API` (when no URL or name can be determined)

> **Inherent limits of static analysis:** URLs built entirely at runtime (e.g. from a method return value, environment variable read in a constructor, or dynamic service discovery via Eureka/Consul) cannot be resolved. FlowLens will fall back to `External API` in those cases.

---

## Context Path Support

If your application sets a `server.servlet.context-path`, FlowLens detects it automatically. All API calls and WebSocket connections inside the dashboard are derived from `window.location`, so the dashboard works correctly regardless of the base path.

For example, if your context path is `/my-service`:

```
http://localhost:8080/my-service/flow-lens/
```

---

## API Reference

FlowLens exposes a small internal REST API (used by the dashboard). You can call it directly if needed.

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow-lens/api/endpoints` | List all discovered entry points |
| `GET` | `/flow-lens/api/diagram?id={id}` | Generate diagram for the given endpoint ID |
| `GET` | `/flow-lens/api/ping` | Health check — returns `"ok"` |

### Endpoint ID format

```
fully.qualified.ClassName#methodName
```

Example:
```
com.example.myapp.controller.UserController#getUser
```

### `GET /flow-lens/api/endpoints` — Response

```json
[
  {
    "id": "com.example.myapp.controller.UserController#getUser",
    "type": "API",
    "label": "GET /api/users/{id}",
    "className": "UserController",
    "methodName": "getUser",
    "group": "UserController"
  },
  {
    "id": "com.example.myapp.listener.OrderListener#onOrderPlaced",
    "type": "CONSUMER",
    "label": "OrderListener.onOrderPlaced",
    "className": "OrderListener",
    "methodName": "onOrderPlaced",
    "group": "OrderListener"
  }
]
```

### `GET /flow-lens/api/diagram?id=...` — Response

```json
{
  "diagram": "sequenceDiagram\n  participant OrderController\n  participant OrderService\n  ...",
  "label": "POST /api/orders",
  "type": "API"
}
```

The `diagram` value is a valid Mermaid `sequenceDiagram` definition.

---

## Security Considerations

The FlowLens dashboard and API are intended for **development and internal environments only**.

If you use Spring Security, the `/flow-lens/**` paths will be protected by whatever security rules you have configured. You may want to explicitly permit them for internal tooling:

```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/flow-lens/**").hasRole("DEVELOPER")
            // ... other rules
        )
        .build();
}
```

Or, to restrict FlowLens to local access only, add this to `application.properties`:

```properties
# Only allow FlowLens in non-production profiles
spring.autoconfigure.exclude=\
  com.dnlabz.flowlens.starter.config.FlowLensAutoConfiguration
```

> **Do not expose `/flow-lens/` to the public internet.** It reveals internal application structure.

---

## Building from Source

```bash
# Clone the repository
git clone https://github.com/your-org/flow-lens.git
cd flow-lens

# Build and install the starter to ~/.m2
./gradlew :starter:clean :starter:publishToMavenLocal

# The frontend is automatically built and embedded into the JAR during the Gradle build.
# Requires Node.js 18+ on the PATH.
```

### Project Structure

```
flow-lens/
├── starter/                            # The Spring Boot starter (what you add as a dependency)
│   └── src/main/java/com/dnlabz/flowlens/starter/
│       ├── analysis/
│       │   ├── StaticCallAnalyzer.java # ASM bytecode traversal + call tree builder
│       │   └── MermaidGenerator.java   # Converts CallNode tree → Mermaid syntax
│       ├── config/
│       │   └── FlowLensAutoConfiguration.java  # Spring Boot auto-configuration
│       ├── discovery/
│       │   └── EndpointDiscovery.java  # Scans Spring beans for entry points
│       ├── model/                      # DiscoveredEndpoint, EntryPointType, etc.
│       ├── store/                      # In-memory TraceStore (live traces)
│       ├── aspect/                     # FlowLensAspect (AOP tracing)
│       └── web/
│           ├── FlowLensController.java # REST API
│           └── FlowLensWebSocketHandler.java   # WebSocket for live status
└── frontend/                           # Next.js dashboard (embedded in the JAR)
    └── src/
        ├── app/page.tsx                # Main dashboard page
        └── components/
            ├── MermaidDiagram.tsx      # Diagram viewer with zoom, pan, step-flow
            └── TraceList.tsx           # Endpoint list sidebar
```

---

## Limitations

- **Static analysis only** — the diagram shows the call graph as seen in bytecode. Branches inside `if/else` blocks are included even if only one path executes at runtime.
- **Proxy-transparent** — Spring AOP CGLIB/ByteBuddy proxies are unwrapped to the real class before analysis.
- **Depth cap** — analysis stops at 12 call levels deep to avoid infinite recursion in complex graphs.
- **Max children** — each method shows at most 30 unique callees to keep diagrams readable.
- **No reactive support** — WebFlux / Project Reactor applications are not supported.

---

## License

Apache 2.0 — see [LICENSE](LICENSE).
