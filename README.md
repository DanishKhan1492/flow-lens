# FlowLens

[![Java](https://img.shields.io/badge/Java-17%2B-blue?logo=openjdk)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.7%20%7C%203.x%20%7C%204.x-brightgreen?logo=springboot)](https://spring.io/projects/spring-boot)
[![Maven Central](https://img.shields.io/maven-central/v/cloud.dnlabz.flowlens/flowlens-spring-boot-starter)](https://central.sonatype.com/artifact/cloud.dnlabz.flowlens/flowlens-spring-boot-starter)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue)](LICENSE)

> **Instant sequence diagrams for every endpoint in your Spring Boot app — zero code changes, zero instrumentation, zero configuration.**

FlowLens is a Spring Boot starter that uses **static bytecode analysis** (via Spring's embedded ASM) to automatically generate interactive Mermaid sequence diagrams for every REST endpoint, Kafka/RabbitMQ consumer, and scheduled task in your application. Just add the dependency and open your browser.

---

## Table of Contents

- [Quick Start](#quick-start)
- [Installation](#installation)
- [Dashboard](#dashboard)
- [How It Works](#how-it-works)
- [Discovered Entry Points](#discovered-entry-points)
- [Sequence Diagram Contents](#sequence-diagram-contents)
- [External System Detection](#external-system-detection)
- [Configuration](#configuration)
- [Context Path Support](#context-path-support)
- [Security](#security)
- [REST API Reference](#rest-api-reference)
- [Building from Source](#building-from-source)
- [Limitations](#limitations)
- [License](#license)

---

## Quick Start

**1. Add the dependency:**

```xml
<!-- Maven -->
<dependency>
    <groupId>cloud.dnlabz.flowlens</groupId>
    <artifactId>flowlens-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

```kotlin
// Gradle (Kotlin DSL)
implementation("cloud.dnlabz.flowlens:flowlens-spring-boot-starter:1.1.0")
```

**2. Start your application and open:**

```
http://localhost:8080/flow-lens/
```

That's it. No `@Enable*` annotations. No properties. No extra beans.

---

## Installation

### Maven

```xml
<dependency>
    <groupId>cloud.dnlabz.flowlens</groupId>
    <artifactId>flowlens-spring-boot-starter</artifactId>
    <version>1.1.0</version>
</dependency>
```

### Gradle (Kotlin DSL)

```kotlin
implementation("cloud.dnlabz.flowlens:flowlens-spring-boot-starter:1.1.0")
```

### Gradle (Groovy DSL)

```groovy
implementation 'cloud.dnlabz.flowlens:flowlens-spring-boot-starter:1.1.0'
```

### Requirements

| Dependency | Version |
|---|---|
| Java | **17 or later** |
| Spring Boot | **2.7.x, 3.x, or 4.x** |
| Spring Web MVC | Required — servlet stack only |

> ⚠️ **WebFlux / reactive stack is not supported.** FlowLens requires `spring-boot-starter-web` (servlet-based).
>
> Spring Boot 2.6 and earlier are not supported.

### Installing from Source

```bash
git clone https://github.com/dnlabz/flow-lens.git
cd flow-lens
./gradlew :starter:clean :starter:publishToMavenLocal
```

Add `mavenLocal()` to your project's `repositories` block:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
```

---

## Dashboard

Once the application is running, navigate to:

```
http://localhost:8080/flow-lens/
```

The dashboard is fully embedded inside the JAR — no separate process, no external CDN, no network calls.

### Layout

```
┌──────────────────────────────────────────────────────────────┐
│  Toolbar: [ ▶ Step Flow ]  [ − 100% + ]  [ 📋 Copy ]        │
├─────────────────────┬────────────────────────────────────────┤
│                     │                                        │
│   Endpoint List     │        Sequence Diagram                │
│                     │                                        │
│  ▸ UserController   │   sequenceDiagram                      │
│  ▸ OrderController  │     Client ->>  UserService: GET /...  │
│  ▸ OrderConsumer    │     UserService ->>+ UserRepo: find()  │
│                     │     UserRepo -->-> PostgreSQL: query() │
│                     │                                        │
└─────────────────────┴────────────────────────────────────────┘
```

### Endpoint List (left sidebar)

- **Grouped by controller / class** — groups are **collapsed by default**; click a group header to expand
- **Filter by type** using the tabs: `All`, `API`, `Consumer`, `Scheduler`
- Click any endpoint to generate and display its sequence diagram

### Sequence Diagram (main area)

- **Zoom** — `+` / `−` buttons or scroll wheel
- **Pan** — click and drag
- **Copy** — copies the raw Mermaid source to clipboard

### Step Flow Mode

Click **▶ Step Flow** to walk through the diagram one interaction at a time:

| Control | Action |
|---|---|
| `◀ Prev` / `Next ▶` | Step backward / forward |
| `▶ Play` / `⏸ Pause` | Auto-advance / pause |
| `← →` or `Space` | Keyboard shortcuts |
| `0.5×` `1×` `2×` | Playback speed |

---

## How It Works

FlowLens performs two phases entirely at startup — no runtime overhead on your hot path.

**Phase 1 — Endpoint Discovery (at application start)**

FlowLens iterates all beans in the `ApplicationContext`, unwraps CGLIB/ByteBuddy proxies to their real target class, and registers any method that carries a recognized entry-point annotation (`@GetMapping`, `@KafkaListener`, `@Scheduled`, etc.).

**Phase 2 — Bytecode Analysis (on first diagram request)**

When you click an endpoint in the dashboard, FlowLens reads the compiled `.class` file for that method and performs a depth-first traversal:

1. Loads bytecode with `ClassLoader.getResourceAsStream()`
2. Uses ASM `MethodVisitor` to walk every `INVOKE*` instruction
3. Resolves interface / abstract type to the concrete Spring bean via `ApplicationContext`
4. Recursively follows internal bean-to-bean calls up to 12 levels deep
5. Detects calls to external systems (databases, REST clients, Kafka, Redis, etc.) and names them using any string literals captured from the bytecode (URLs, topic names, workflow names)
6. Builds a `CallNode` tree and converts it to a Mermaid `sequenceDiagram` string
7. Results are memoised — repeated requests for the same endpoint are instant

```
Your code (.class files in classpath)
             │
             ▼
  StaticCallAnalyzer  ◄──  Spring ASM (bundled)
             │
             ▼
       CallNode tree
             │
             ▼
    MermaidGenerator
             │
             ▼
  sequenceDiagram string  ──►  Dashboard (Mermaid.js)
```

---

## Discovered Entry Points

FlowLens detects three types of entry points at startup:

### REST / HTTP (`type: API`)

Any `@RestController` or `@Controller` method with a request mapping annotation:

```java
@GetMapping("/users/{id}")
public UserDto getUser(@PathVariable Long id) { … }

@PostMapping("/orders")
public ResponseEntity<Order> createOrder(@RequestBody OrderRequest req) { … }
```

**Auto-excluded paths** (Swagger UI, OpenAPI spec, Spring Actuator):

| Excluded prefix | Reason |
|---|---|
| `/swagger-ui/**`, `/swagger/**` | Springfox / SpringDoc UI |
| `/v2/api-docs`, `/v3/api-docs`, `/api-docs/**` | OpenAPI spec endpoints |
| `/webjars/**` | Static web assets |
| `/actuator/**` | Spring Boot Actuator |
| `/error` | Spring default error controller |

Beans from `org.springdoc`, `springfox`, and `io.swagger` packages are also skipped entirely.

### Message Consumers (`type: CONSUMER`)

| Annotation | Broker |
|---|---|
| `@KafkaListener` / `@KafkaListeners` | Apache Kafka (Spring Kafka) |
| `@RabbitListener` / `@RabbitListeners` | RabbitMQ (Spring AMQP) |
| `@SqsListener` | AWS SQS (Spring Cloud AWS) |

### Scheduled Tasks (`type: SCHEDULER`)

Any method annotated with `@Scheduled`.

---

## Sequence Diagram Contents

### Internal participants

Any Spring-managed bean that is directly or transitively called by the entry point method is shown as a labelled participant. FlowLens automatically:

- **Flattens self-calls** — private helper methods on the same class are not shown as separate participants; their external calls surface under the calling participant
- **Deduplicates** — if the same external system is called multiple times, it appears as one participant
- **Prunes unused participants** — any participant declared but with no arrows pointing to it is automatically removed

### Filtered out (never shown)

- Spring framework internals (`org.springframework.*`, `org.hibernate.*`, `reactor.*`, `io.netty.*`, …)
- JDK / JVM classes (`java.*`, `javax.*`, `jakarta.*`, `sun.*`)
- DTOs, entities, model objects (packages or names containing `.dto.`, `.entity.`, `.model.`, `.vo.`)
- Configuration / properties beans
- Third-party libraries (Jackson, BouncyCastle, protobuf, Micrometer, …)

---

## External System Detection

External boundary systems are represented as **named participants**. FlowLens extracts the most specific name available from bytecode and application properties.

### Detection table

| System | Example participant | How detected |
|---|---|---|
| **Database** | `PostgreSQL`, `MySQL`, `H2` | Reads `spring.datasource.url` / `driver-class-name` / `spring.r2dbc.url` / `spring.cassandra.contact-points` |
| **REST / HTTP** | `payments.acme.com`, `payment-service` | See resolution chain below |
| **Kafka** | `topic:order-events` | Topic string literal passed to `KafkaTemplate.send()` |
| **RabbitMQ / SQS / SNS** | `queue:order-queue` | Queue / exchange / routing-key string literal |
| **Cadence** | `OrderWorkflow [Cadence]` | Workflow/activity name string passed to `WorkflowClient` |
| **Temporal** | `ShipmentWorkflow [Temporal]` | Workflow name string passed to `WorkflowClient` |
| **Redis** | `Redis` | `RedisTemplate`, Lettuce, Jedis, Redisson |
| **Elasticsearch** | `Elasticsearch` | `ElasticsearchOperations`, Elastic Java client |
| **MongoDB** | `MongoDB` | `MongoTemplate`, `ReactiveMongoTemplate` |
| **gRPC** | stub class name | gRPC stub class suffix detection |

### REST service name resolution

Resolution is attempted in this priority order:

| Priority | Pattern | Example → Result |
|---|---|---|
| 1 | Absolute URL string literal | `"https://payments.acme.com/v1"` → `payments.acme.com` |
| 2 | `@FeignClient(url = "https://…")` | URL host extracted from annotation |
| 3 | `@FeignClient(name = "payment-service")` | `payment-service` |
| 4 | `@Value("${svc.url}") String baseUrl` field | Property resolved from `Environment`, host extracted |
| 5 | `baseUrl + "/path"` string concat | Host carried through `makeConcatWithConstants` |
| 6 | Fallback | `External API` |

> **Static analysis limits:** URLs resolved at runtime from method return values, environment variables set outside `application.properties`, or service-discovery registries (Eureka, Consul) cannot be determined statically and will fall back to `External API`.

---

## Configuration

FlowLens works out of the box with no required properties. It reads standard Spring properties automatically for database name detection (`spring.datasource.*`, `spring.r2dbc.*`, etc.).

### Disable FlowLens

To exclude FlowLens in a specific profile (e.g. production), use Spring Boot's auto-configuration exclusion:

```properties
# application-prod.properties
spring.autoconfigure.exclude=cloud.dnlabz.flowlens.starter.config.FlowLensAutoConfiguration
```

Or, with Gradle's `developmentOnly` scope (ensures it never goes to production):

```kotlin
// build.gradle.kts
developmentOnly("cloud.dnlabz.flowlens:flowlens-spring-boot-starter:1.1.0")
```

---

## Context Path Support

If your application uses `server.servlet.context-path`, FlowLens detects it automatically via `window.location` — no extra configuration needed.

```properties
server.servlet.context-path=/my-service
```

Dashboard URL becomes:

```
http://localhost:8080/my-service/flow-lens/
```

---

## Security

> ⚠️ **FlowLens is for development and internal tooling only.** It exposes your application's internal call structure. Never expose `/flow-lens/**` to untrusted networks.

### Restricting access with Spring Security

```java
@Bean
SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    return http
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/flow-lens/**").hasRole("ADMIN")
            .anyRequest().authenticated()
        )
        .build();
}
```

### Permit all (internal-only deployments behind a VPN)

```java
.requestMatchers("/flow-lens/**").permitAll()
```

---

## REST API Reference

The dashboard communicates with these internal endpoints. You can also call them directly for scripting or CI.

| Method | Path | Description |
|---|---|---|
| `GET` | `/flow-lens/api/endpoints` | List all discovered entry points |
| `GET` | `/flow-lens/api/diagram?id={id}` | Generate diagram for the given endpoint ID |
| `GET` | `/flow-lens/api/ping` | Health check — returns `"ok"` |

### Endpoint ID format

```
fully.qualified.ClassName#methodName
```

### `GET /flow-lens/api/endpoints` — Response

```json
[
  {
    "id": "com.example.app.controller.UserController#getUser",
    "type": "API",
    "label": "GET /api/users/{id}",
    "className": "UserController",
    "methodName": "getUser",
    "group": "UserController"
  },
  {
    "id": "com.example.app.listener.OrderListener#onOrderPlaced",
    "type": "CONSUMER",
    "label": "OrderListener.onOrderPlaced",
    "className": "OrderListener",
    "methodName": "onOrderPlaced",
    "group": "OrderListener"
  }
]
```

**`type` values:** `API` | `CONSUMER` | `SCHEDULER`

### `GET /flow-lens/api/diagram?id=...` — Response

```json
{
  "diagram": "sequenceDiagram\n  participant Client\n  participant UserController\n  ...",
  "label": "GET /api/users/{id}",
  "type": "API"
}
```

The `diagram` field is a valid Mermaid `sequenceDiagram` string, ready to be passed directly to `mermaid.render()`.

---

## Building from Source

### Prerequisites

- Java 17+
- Node.js 18+ (for the embedded Next.js frontend)
- Gradle wrapper included (`./gradlew`)

### Build and install locally

```bash
git clone https://github.com/dnlabz/flow-lens.git
cd flow-lens

# Build the starter JAR and install to ~/.m2/repository
./gradlew :starter:clean :starter:publishToMavenLocal
```

The Gradle build automatically:
1. Runs `npm ci && npm run build` inside `frontend/`
2. Copies the Next.js static export into `starter/src/main/resources/META-INF/resources/flow-lens/`
3. Compiles the Java source and packages everything into the JAR

### Project structure

```
flow-lens/
├── starter/                              # Spring Boot starter (the published artifact)
│   ├── build.gradle.kts
│   └── src/main/java/com/dnlabz/flowlens/starter/
│       ├── analysis/
│       │   ├── StaticCallAnalyzer.java   # ASM bytecode walker + call tree builder
│       │   ├── MermaidGenerator.java     # CallNode tree → Mermaid sequenceDiagram
│       │   ├── CallNode.java             # Call tree node
│       │   └── MethodCallInfo.java       # Per-instruction metadata (record)
│       ├── config/
│       │   └── FlowLensAutoConfiguration.java
│       ├── discovery/
│       │   └── EndpointDiscovery.java    # Entry-point scanner
│       ├── model/
│       │   ├── DiscoveredEndpoint.java
│       │   └── EntryPointType.java
│       └── web/
│           └── FlowLensController.java   # REST API endpoints
│
└── frontend/                             # Next.js dashboard (embedded in JAR)
    ├── package.json
    └── src/
        ├── app/page.tsx                  # Main dashboard page
        └── components/
            ├── MermaidDiagram.tsx        # Diagram viewer (zoom, pan, step-flow)
            └── TraceList.tsx             # Endpoint list sidebar
```

---

## Limitations

| Limitation | Detail |
|---|---|
| **Static analysis only** | Both branches of an `if/else` appear in the diagram regardless of what actually runs at runtime. |
| **No WebFlux support** | Reactive / Project Reactor pipelines are not traversed. |
| **Depth cap** | Analysis stops at **12 call levels** deep to prevent runaway traversal. |
| **Max callees per method** | At most **30 unique callees** per method to keep diagrams readable. |
| **Dynamic URLs** | URLs computed at runtime show as `External API`. |
| **Lambda / method reference bodies** | Calls inside lambdas and method references are not traversed. |
| **Async / threaded calls** | Code executed inside `CompletableFuture`, `new Thread(...)`, `@Async` methods, or submitted to an `Executor` / thread pool is **not traced**. Only the call chain on the original request thread is captured; any work handed off to another thread will not appear in the sequence diagram. |
| **Off-classpath modules** | Classes not loadable from the context class loader at analysis time are silently skipped. |

---

## License

Licensed under the **Apache License, Version 2.0** — see [LICENSE](LICENSE) for details.

---

<p align="center">Made for developers who want to understand their own codebase — instantly.</p>
