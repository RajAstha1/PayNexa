plugins { `java-library` }

dependencies {
    // Real PostgreSQL binaries run in-process-managed, so tests need no Docker daemon.
    api(libs.zonky.embedded.postgres)
    api(platform(libs.zonky.postgres.binaries.bom))
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-database-postgresql")
    api("org.postgresql:postgresql")
    api("org.junit.jupiter:junit-jupiter-api")
}
