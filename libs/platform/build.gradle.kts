plugins { `java-library` }

// Web types are compileOnly: each service brings its own Spring Boot web stack.
dependencies {
    api("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-context")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("io.micrometer:micrometer-core")

    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("io.micrometer:micrometer-core")
    testRuntimeOnly("ch.qos.logback:logback-classic")
}
