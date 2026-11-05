plugins { java; application }
repositories { mavenLocal(); mavenCentral() }
java { toolchain.languageVersion.set(JavaLanguageVersion.of(17)) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17); options.compilerArgs.add("-parameters") }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("io.github.59alex:pg-task-lease-spring-boot-starter:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    runtimeOnly("org.postgresql:r2dbc-postgresql")
}
application { mainClass.set("example.ReportApplication") }
