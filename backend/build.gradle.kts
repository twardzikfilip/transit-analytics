plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.transitanalytics"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation(project(":zbiorkom-feed"))
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.apache.commons:commons-csv:1.14.1")
	// OpenAPI 3 spec at /v3/api-docs and Swagger UI at /swagger-ui.html. springdoc 3.x targets Spring Boot 4.
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0")
	// Compile-time too: the GTFS import uses the driver's COPY API.
	implementation("org.postgresql:postgresql")
	implementation("net.ttddyy.observation:datasource-micrometer-spring-boot:2.3.0")
	// Keep in step with the OpenTelemetry SDK version Spring Boot manages (2.28.x <-> SDK 1.62 for Boot 4.1.2).
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.1-alpha")
	implementation("io.jsonwebtoken:jjwt-api:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
	testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// Test coverage (JaCoCo): every test run writes a report to build/reports/jacoco/test (html/index.html and an XML
// file for CI). A plain `./gradlew test` covers the unit tests; with INTEGRATION_TESTS=true the database tests count
// too. `./gradlew check` also fails when line coverage drops below the floor below.
jacoco {
	toolVersion = "0.8.13"
}

tasks.test {
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
		csv.required = false
	}
}

// Line coverage floors, a little under what the tests reach: unit tests alone (~54%), and with the database tests
// (~95%, as CI runs them). Raise them as coverage grows.
val integrationTests = System.getenv("INTEGRATION_TESTS") == "true"

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	violationRules {
		rule {
			limit {
				counter = "LINE"
				minimum = (if (integrationTests) "0.90" else "0.50").toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}
