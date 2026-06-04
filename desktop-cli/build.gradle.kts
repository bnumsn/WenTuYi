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
