// ─────────────────────────────────────────────────────────────────────────────
// Traffic Cam — Root Gradle Build
// Plugin declarations: all applied false so subprojects opt-in explicitly.
// ─────────────────────────────────────────────────────────────────────────────
plugins {
    // Spring Boot 4.x — requires Spring Framework 7.x; uses Spring milestone repo
    id("org.springframework.boot") version "4.0.0-M3" apply false

    // Spring dependency management — BOM import support
    id("io.spring.dependency-management") version "1.1.7" apply false

    // GraalVM native image compilation
    id("org.graalvm.buildtools.native") version "0.10.6" apply false

    // Fat/shadow JAR — used by the agent module
    // Using the community fork (goooler) which ships ASM 9.7+ and supports Java 21 class files.
    id("io.github.goooler.shadow") version "8.1.8" apply false
}

// ─────────────────────────────────────────────────────────────────────────────
// Common Java 21 toolchain for all Java subprojects
// ─────────────────────────────────────────────────────────────────────────────
subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:unchecked"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        jvmArgs("-XX:+EnableDynamicAgentLoading")
    }
}
