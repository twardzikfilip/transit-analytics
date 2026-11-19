// transit-recorder: polls the zbiorkom.live feed every 10 seconds and stores every position in position_sample,
// independently of the backend, so the backend can be restarted without losing data. See README, "Recorder".
plugins {
	java
	jacoco
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

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
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("io.opentelemetry.instrumentation:opentelemetry-logback-appender-1.0:2.28.1-alpha")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	// The database tests create the schema the way the backend does: with its Flyway migrations.
	testImplementation("org.springframework.boot:spring-boot-starter-flyway")
	testImplementation("org.flywaydb:flyway-database-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The backend's migrations, for the database tests only (the recorder never migrates the real database).
sourceSets {
	test {
		resources {
			srcDir(rootProject.file("src/main/resources/db"))
		}
	}
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
	archiveFileName = "transit-recorder.jar"
}

// `./gradlew bootRun` in backend/ starts the backend only; the recorder starts when asked for by name.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	onlyIf { gradle.startParameter.taskNames.any { it.startsWith(":recorder:") } }
}

tasks.withType<Test> {
	useJUnitPlatform()
}

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
	}
}

val integrationTests = System.getenv("INTEGRATION_TESTS") == "true"

tasks.jacocoTestCoverageVerification {
	dependsOn(tasks.test)
	violationRules {
		rule {
			limit {
				counter = "LINE"
				minimum = (if (integrationTests) "0.85" else "0.60").toBigDecimal()
			}
		}
	}
}

tasks.check {
	dependsOn(tasks.jacocoTestCoverageVerification)
}
