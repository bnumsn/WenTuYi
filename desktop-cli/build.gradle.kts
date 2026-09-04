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
    standardOutput = rootProject.file("protocol-fixtures/vectors.txt").outputStream()
}
