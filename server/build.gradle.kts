// The FediFerry server: one process per host, serving any number of separate
// projects. `fediferry serve` runs it; `fediferry project …` manages projects.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("app.fediferry.server.MainKt")
    applicationName = "fediferry"
}

sqldelight {
    databases {
        create("ServerDatabase") {
            packageName.set("app.fediferry.server.db")
            dialect("app.cash.sqldelight:sqlite-3-38-dialect:${libs.versions.sqldelight.get()}")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.clikt)
    implementation(libs.logback.classic)
    // WebP for ImageIO, so sizes and thumbnails work for every picture a phone sends.
    implementation(libs.twelvemonkeys.webp)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.named<JavaExec>("run") {
    args = listOf("serve")
    workingDir = rootProject.file("server")
}
