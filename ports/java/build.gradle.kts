plugins {
    `java-library`
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

tasks.test {
    useJUnitPlatform()
    // The conformance suite the harness reads. A working tree reads the repository's own tree;
    // `sharder.conformance.dir` substitutes another.
    systemProperty(
        "sharder.conformance.dir",
        providers.systemProperty("sharder.conformance.dir")
            .getOrElse(rootDir.resolve("../../conformance").canonicalPath),
    )
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
