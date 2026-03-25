plugins {
    id("io.spring.dependency-management")
    id("java-library")
    id("maven-publish")
}

group   = "com.dnlabz.flowlens"
version = "1.0.0"

dependencyManagement {
    imports {
        // Compile against the oldest supported Boot version (2.7.x = Spring Framework 5.3.x).
        // This guarantees no Spring 6.x-only API leaks into the library bytecode.
        // The library works at runtime with Spring Boot 2.7.x, 3.x, and 4.x.
        mavenBom("org.springframework.boot:spring-boot-dependencies:2.7.18")
    }
}

dependencies {
    // Embedded-library pattern: compileOnly so the host app provides these at runtime
    compileOnly("org.springframework.boot:spring-boot-autoconfigure")
    compileOnly("org.springframework.boot:spring-boot-starter-web")
    // Declared as 'api' so consumers get websocket + aop transitively
    api("org.springframework.boot:spring-boot-starter-websocket")
    api("org.springframework.boot:spring-boot-starter-aop")

    // Optional: detect @KafkaListener / @RabbitListener without hard-coding the dep
    compileOnly("org.springframework.kafka:spring-kafka")
    compileOnly("org.springframework.amqp:spring-rabbit")

    // Generates spring-configuration-metadata.json from @ConfigurationProperties
    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor")

    // Jackson for JSON serialisation of trace records
    compileOnly("com.fasterxml.jackson.core:jackson-databind")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-aop")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
    options.encoding = "UTF-8"
}

// Produce a plain library JAR (no fat-jar, no Spring Boot repackage)
tasks.jar { enabled = true }

// ── Embedded dashboard (Next.js static export) ────────────────────────────────

val frontendDir = rootProject.file("frontend")

val npmInstall by tasks.registering(Exec::class) {
    description = "Install frontend npm dependencies"
    group = "frontend"
    workingDir = frontendDir
    val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
    commandLine(npm, "install", "--prefer-offline")
    // Only re-run if package.json changed
    inputs.file(frontendDir.resolve("package.json"))
    outputs.dir(frontendDir.resolve("node_modules"))
}

val buildFrontend by tasks.registering(Exec::class) {
    description = "Build the Next.js static export"
    group = "frontend"
    dependsOn(npmInstall)
    workingDir = frontendDir
    val npm = if (System.getProperty("os.name").lowercase().contains("windows")) "npm.cmd" else "npm"
    commandLine(npm, "run", "build")
    // Re-run when source files or config change
    inputs.dir(frontendDir.resolve("src"))
    inputs.file(frontendDir.resolve("package.json"))
    inputs.file(frontendDir.resolve("next.config.ts"))
    outputs.dir(frontendDir.resolve("out"))
}

// Copy the static export output into the JAR resource path so Spring Boot serves it
val copyFrontendResources by tasks.registering(Copy::class) {
    description = "Copy Next.js static export into starter resources"
    group = "frontend"
    dependsOn(buildFrontend)
    from(frontendDir.resolve("out"))
    into(layout.buildDirectory.dir("generated-resources/main/META-INF/resources/flow-lens"))
}

// Wire generated resources into the compilation source set
sourceSets.main.configure {
    resources.srcDir(layout.buildDirectory.dir("generated-resources/main"))
}

tasks.named("processResources") {
    dependsOn(copyFrontendResources)
}

// ── Maven publishing ───────────────────────────────────────────────────────────

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId    = project.group.toString()
            artifactId = "flowlens-spring-boot-starter"
            version    = project.version.toString()
            // Resolve BOM-managed versions so the published POM contains concrete versions
            versionMapping {
                usage("java-api")     { fromResolutionOf("runtimeClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }
        }
    }
}
