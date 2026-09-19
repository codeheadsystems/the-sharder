import com.codeheadsystems.sharder.gradle.VerifyDocLinksTask
import com.codeheadsystems.sharder.gradle.VerifyDocStyleTask
import com.codeheadsystems.sharder.gradle.VerifyPomTask
import com.codeheadsystems.sharder.gradle.VerifyUnsignedTask

plugins {
    `java-library`
    `maven-publish`
    jacoco
}

group = "com.codeheadsystems"
version = "0.1.0-SNAPSHOT"

// The published artifact requires `java.base` and nothing else, under `docs/design/adr/0032`.
// Every dependency below is test scope, which reaches no consumer.
repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.bouncycastle)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    // The JDK floor is 21, under `docs/design/adr/0031`. The build compiles against the Java 21
    // API whatever JDK runs it.
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    // The main source set carries `module-info.java` and requires nothing, so the module path buys
    // the build nothing and the tests run on the classpath.
    modularity.inferModulePath.set(false)
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = false
}

// The repository the documentation checks read, two directories above the Gradle root, because
// the documents belong to no port.
val documentationRoot = layout.projectDirectory.dir("../..")

tasks.test {
    useJUnitPlatform()
    // The conformance suite the harness reads. A working tree reads the repository's own tree;
    // `sharder.conformance.dir` substitutes another.
    systemProperty(
        "sharder.conformance.dir",
        providers.systemProperty("sharder.conformance.dir")
            .getOrElse(rootDir.resolve("../../conformance").canonicalPath),
    )
    // The run report a declaration publishes. An ordinary run writes it into the build directory;
    // a maintainer declaring conformance names the path the declaration carries.
    systemProperty(
        "sharder.conformance.report",
        providers.systemProperty("sharder.conformance.report")
            .getOrElse(layout.buildDirectory.file("conformance-report.txt").get().asFile.path),
    )
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// The publication exists so the descriptor the dependency check reads exists. Nothing is published
// until every port passes in its own harness, under `docs/design/adr/0083`.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

val verifyUnsignedComparisons by tasks.registering(VerifyUnsignedTask::class) {
    description = "Refuses a signed comparison of a 64-bit value on the placement path."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    dependsOn(tasks.classes)
    classesDirectory.set(layout.buildDirectory.dir("classes/java/main"))
    packages.set(
        setOf(
            "com/codeheadsystems/sharder/core/internal/hash/",
            "com/codeheadsystems/sharder/core/internal/placement/",
        )
    )
    exempt.set(setOf("com/codeheadsystems/sharder/core/internal/hash/U64"))
}

// The Markdown files the two documentation checks read, which are their inputs. The repository
// root holds the build output as well, so a task declaring it whole would wait on everything the
// build writes.
val documentationFiles = documentationRoot.asFileTree.matching {
    include("docs/**/*.md", "ports/**/*.md", "README.md", "CONTRIBUTING.md")
    exclude("**/build/**", "**/.git/**", "**/.gradle/**")
}

val verifyDocLinks by tasks.registering(VerifyDocLinksTask::class) {
    description = "Proves that every documentation cross-reference resolves."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    repositoryRoot.set(documentationRoot)
    documents.from(documentationFiles)
}

val verifyDocStyle by tasks.registering(VerifyDocStyleTask::class) {
    description = "Reports the four style findings, and fails on none of them."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    repositoryRoot.set(documentationRoot)
    documents.from(documentationFiles)
}

val verifyPomDependencies by tasks.registering(VerifyPomTask::class) {
    description = "Refuses a published descriptor carrying a consumer-reaching dependency."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    val generate = tasks.named("generatePomFileForMavenPublication")
    dependsOn(generate)
    pom.set(layout.buildDirectory.file("publications/maven/pom-default.xml"))
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(
        verifyUnsignedComparisons,
        verifyDocLinks,
        verifyDocStyle,
        verifyPomDependencies,
        tasks.jacocoTestReport,
        tasks.jacocoTestCoverageVerification,
    )
}
