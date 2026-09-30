import java.io.ByteArrayOutputStream

plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared-protocol"))
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

tasks.test {
    // Integration tests launch independent JVMs against the real CLI entry point.
    systemProperty("wentuyi.test.classpath", sourceSets["test"].runtimeClasspath.asPath)
}

application {
    mainClass.set("com.wentuyi.cli.WentuyiCliKt")
}

// Regenerates the canonical protocol vectors consumed by both :shared-protocol and :app
// tests. Writes protocol-fixtures/vectors.txt. Run intentionally — salt/IV are random so
// every run rewrites the frozen payloads; re-run both test suites afterwards.
tasks.register<JavaExec>("generateFixtures") {
    group = "verification"
    description = "Regenerate protocol-fixtures/vectors.txt from the authoritative codec"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.wentuyi.cli.FixtureGenerator")
    // Configuration must never touch the frozen vectors. Capture first, publish only
    // after a successful generator run, and close the file even if writing fails.
    val generated = ByteArrayOutputStream()
    standardOutput = generated
    doFirst { generated.reset() }
    doLast {
        rootProject.file("protocol-fixtures/vectors.txt").outputStream().use { generated.writeTo(it) }
    }
}
