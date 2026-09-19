plugins {
    `kotlin-dsl`
}

// The build's own dependencies reach no consumer, and the dependency policy of
// `docs/design/adr/0032` does not bind them.
repositories {
    mavenCentral()
}

dependencies {
    implementation("org.ow2.asm:asm:9.7.1")
}
