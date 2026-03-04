plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
    id("org.graalvm.buildtools.native") version "0.11.3"
}

group = "dev.llmreview"
version = "0.2.0"

repositories {
    mavenCentral()
}

val cliktVersion = "5.1.0"
val mordantVersion = "2.7.2"
val ktorVersion = "3.1.1"
val jgitVersion = "7.1.0.202411261347-r"

dependencies {
    // CLI
    implementation("com.github.ajalt.clikt:clikt:$cliktVersion")
    implementation("com.github.ajalt.mordant:mordant:$mordantVersion")

    // Git
    implementation("org.eclipse.jgit:org.eclipse.jgit:$jgitVersion")

    // HTTP / LLM API
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
}

application {
    mainClass.set("dev.llmreview.MainKt")
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("llmreview")
            mainClass.set("dev.llmreview.MainKt")
            buildArgs.addAll(
                "--no-fallback",
                "--static",
                "--libc=musl",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=kotlin",
                "--initialize-at-build-time=kotlinx",
                "--initialize-at-build-time=org.slf4j",
                "--initialize-at-build-time=io.ktor",
            )
            // Uses JAVA_HOME (set to GraalVM) when toolchainDetection is off
        }
    }
    toolchainDetection.set(false)
}

tasks.test {
    useJUnitPlatform()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

// Fat JAR for distribution
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "dev.llmreview.MainKt"
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
