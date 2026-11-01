plugins { `java-library` }

dependencies {
    // main source set: LocalStack, a Docker-free launcher for hands-on checking (./gradlew :system-tests:runLocal)
    implementation(project(":libs:test-support"))
    implementation(project(":libs:contracts"))
    implementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(project(":libs:test-support"))
    testImplementation(project(":libs:contracts"))
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
    testImplementation("tools.jackson.core:jackson-databind")
    testImplementation("org.awaitility:awaitility")
}

// The system test launches the real executable jars, so they must be built first.
val services = listOf("identity-service", "payments-service", "notification-service")

tasks.withType<Test>().configureEach {
    services.forEach { dependsOn(":services:$it:bootJar") }
    services.forEach { name ->
        systemProperty("jar.$name", project(":services:$name").layout.buildDirectory
            .file("libs/$name-${project.version}.jar").get().asFile.absolutePath)
    }
    systemProperty("scripts.dir", rootProject.layout.projectDirectory.dir("infra/postgres").asFile.absolutePath)
    systemProperty("java.bin", javaLauncher.get().executablePath.asFile.absolutePath)
    // Not an incremental unit test: always run when requested.
    outputs.upToDateWhen { false }
    maxHeapSize = "512m"
}

tasks.register<JavaExec>("runLocal") {
    description = "Starts PostgreSQL, Kafka and the three services locally (sandbox) and keeps them running."
    group = "application"
    services.forEach { dependsOn(":services:$it:bootJar") }
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("io.paynexa.system.LocalStack")
    services.forEach { name ->
        systemProperty("jar.$name", project(":services:$name").layout.buildDirectory
            .file("libs/$name-${project.version}.jar").get().asFile.absolutePath)
    }
    systemProperty("scripts.dir", rootProject.layout.projectDirectory.dir("infra/postgres").asFile.absolutePath)
    systemProperty("java.bin", javaLauncher.get().executablePath.asFile.absolutePath)
    jvmArgs("-Xshare:off")
}
