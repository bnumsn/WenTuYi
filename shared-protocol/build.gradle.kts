plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    testImplementation(kotlin("test"))
}

// Exercise the same bounded-heap conditions that previously let a tiny forged header OOM.
tasks.test {
    maxHeapSize = "256m"
}

// The repo-level canonical vectors are the single source of truth shared with :app. Putting
// the directory on the test classpath lets VectorContractTest read /vectors.txt as a resource.
sourceSets {
    test {
        resources.srcDir(rootProject.file("protocol-fixtures"))
    }
}
