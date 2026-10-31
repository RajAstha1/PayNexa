plugins { `java-library` }

// Spring types are compileOnly: each service brings its own Spring Boot stack and versions.
dependencies {
    api("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-context")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")
    compileOnly("org.springframework.security:spring-security-oauth2-resource-server")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("io.micrometer:micrometer-core")

    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-context")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.security:spring-security-oauth2-jose")
    testImplementation("org.springframework.security:spring-security-oauth2-resource-server")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("io.micrometer:micrometer-core")
    testRuntimeOnly("ch.qos.logback:logback-classic")
}
