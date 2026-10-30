plugins {
    java
    checkstyle
    id("org.springframework.boot") version "4.0.2"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.openapi.generator") version "7.17.0"
}

group = "com.example"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyLocking {
    lockMode = org.gradle.api.artifacts.dsl.LockMode.STRICT
    lockAllConfigurations()
}

checkstyle {
    toolVersion = "12.3.1"
    configFile = file("checkstyle.xml")
    maxWarnings = 0
    isIgnoreFailures = false
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Each cached Spring test context owns a pool; leave capacity for independent Cloud verification.
    systemProperty("spring.datasource.hikari.maximum-pool-size", "4")
    systemProperty("spring.test.context.cache.maxSize", "8")
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.bootJar {
    archiveFileName = "debt-management.jar"
}

tasks.jar {
    enabled = false
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"
    networkTimeout = 60_000
}

openApiGenerate {
    generatorName = "spring"
    templateDir = rootProject.file("openapi-templates").absolutePath
    inputSpec = rootProject.file("../contracts/openapi.yaml").absolutePath
    outputDir = layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath
    apiPackage = "com.example.debt.generated.api"
    modelPackage = "com.example.debt.generated.model"
    configOptions = mapOf(
        "interfaceOnly" to "true",
        "skipDefaultInterface" to "true",
        "useTags" to "true",
        "useSpringBoot3" to "true",
        "useSpringBuiltInValidation" to "true",
        "useBeanValidation" to "true",
        "openApiNullable" to "false",
        "annotationLibrary" to "none",
        "documentationProvider" to "none",
        "hideGenerationTimestamp" to "true",
        "useResponseEntity" to "true"
    )
    globalProperties = mapOf("apis" to "", "models" to "", "supportingFiles" to "false")
}

sourceSets.main {
    java.srcDir(layout.buildDirectory.dir("generated/openapi/src/main/java"))
}

tasks.compileJava {
    dependsOn(tasks.openApiGenerate)
}

tasks.checkstyleMain {
    source = fileTree("src/main/java")
}

openApiValidate {
    inputSpec = rootProject.file("../contracts/openapi.yaml").absolutePath
}

tasks.check {
    dependsOn(tasks.openApiValidate)
}
