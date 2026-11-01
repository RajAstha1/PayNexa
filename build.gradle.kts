plugins {
    alias(libs.plugins.spring.boot) apply false
}

allprojects {
    group = "io.paynexa"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")

    // Pin the full resolved dependency graph in gradle.lockfile so builds are reproducible and vulnerability
    // scanners (OSV, Dependabot) have an exact list to check. Refresh with: ./gradlew resolveAndLockAll --write-locks
    dependencyLocking { lockAllConfigurations() }
    tasks.register("resolveAndLockAll") {
        notCompatibleWithConfigurationCache("Filters configurations at execution time")
        doFirst {
            require(gradle.startParameter.isWriteDependencyLocks) { "Run with --write-locks" }
        }
        doLast {
            configurations.filter { it.isCanBeResolved }.forEach { it.resolve() }
        }
    }

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
    }

    dependencies {
        "api"(platform(rootProject.libs.spring.boot.bom))
        "annotationProcessor"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"(platform(rootProject.libs.spring.boot.bom))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testImplementation"("org.assertj:assertj-core")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform {
            // Heavy load tests are opt-in: ./gradlew loadTest
            excludeTags("load")
        }
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
        jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    }
}
