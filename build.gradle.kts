plugins { `java-library`; `maven-publish` }
group = "io.github.59alex"
version = "0.1.0"
repositories { mavenLocal(); mavenCentral() }
val testJavaVersion = providers.gradleProperty("testJavaVersion").map(String::toInt).getOrElse(JavaVersion.current().majorVersion.toInt())
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(testJavaVersion))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar(); withJavadocJar()
}
dependencies {
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    api("io.github.59alex:pg-task-lease:0.3.0")
    api("org.springframework.boot:spring-boot-starter-r2dbc")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    runtimeOnly("io.r2dbc:r2dbc-pool")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.1.2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.postgresql:r2dbc-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.encoding = "UTF-8" }
tasks.withType<Jar>().configureEach { from("LICENSE") { into("META-INF") } }
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("failed", "skipped") }
}
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("pg-task-lease-spring-boot-starter")
                description.set("Spring Boot integration and an opt-in bounded worker for PostgreSQL task leases")
                url.set("https://github.com/dlorian59/pg-task-lease-spring-boot-starter")
                licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
                developers { developer { id.set("dlorian59"); name.set("Aleksandr Kashevarov") } }
            }
        }
    }
}

tasks.test { useJUnitPlatform { excludeTags("postgres") } }

tasks.register<Test>("integrationTest") {
    description = "Runs worker integration tests against an explicitly supplied disposable PostgreSQL database"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("postgres") }
    shouldRunAfter(tasks.test)
}
