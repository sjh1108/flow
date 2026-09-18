plugins {
    java
    id("org.springframework.boot") version "4.1.1"
}

// Spring Boot 4 drops the io.spring.dependency-management plugin in favour of
// Gradle's native platform support; the BOM is imported explicitly below.

group = "com.flow"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    // repo1.maven.org rather than the mavenCentral() default (repo.maven.apache.org):
    // the latter rate-limits aggressively behind shared egress.
    maven { url = uri("https://repo1.maven.org/maven2") }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.1"))

    // Spring Boot 4 renamed spring-boot-starter-web -> spring-boot-starter-webmvc
    // and split auto-configuration into per-technology modules.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")

    runtimeOnly("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Boot 4 splits test auto-configuration per technology; MockMvc support
    // moved out of the monolithic spring-boot-test-autoconfigure jar.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // H2 is on the runtime classpath so the 'dev' profile can run the real
    // migrations without MySQL; production uses the MySQL driver above.
    runtimeOnly("com.h2database:h2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:deprecation"))
}
