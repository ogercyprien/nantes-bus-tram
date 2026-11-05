plugins {
    alias(libs.plugins.kotlin.jvm)
}

/*
 * Pure-JVM transit core (no Android dependency): GTFS parsing, the packed network format, the
 * in-memory TransitNetwork, the RAPTOR router and departure-board composition. Pure JVM so that
 *  - the very same converter runs at build time (`./gradlew :transit:buildSeed`, which writes the
 *    bundled `app/src/main/assets/seed/network.bin`) and on the device (daily GTFS refresh), and
 *  - all of it is unit-testable on the JVM without an emulator.
 */

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Only the seed tool (tools/SeedBuild.kt) parses JSON; the runtime core is dependency-free.
    implementation(libs.kotlinx.serialization.json)
    // SIRI XML is parsed through the XmlPullParser API, which Android provides at runtime
    // (android.util.Xml); kxml2 supplies the same API and an implementation for JVM compile/tests.
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
    testImplementation(libs.junit)
}

tasks.register<JavaExec>("convertBenchmark") {
    group = "transit"
    description = "Times a GTFS zip -> packed network conversion (-Pgtfs=/path/to/gtfs.zip)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.bustramnantes.transit.tools.ConvertBenchmarkKt")
    args(providers.gradleProperty("gtfs").getOrElse(""), layout.buildDirectory.file("network.bin").get().asFile.path)
    maxHeapSize = (providers.gradleProperty("heap").getOrElse("512m"))
}

// Seed data (app/src/main/assets/seed/{walk_graph.bin, network.bin, bacs_de_loire.zip}): compiled from
// open data and recorded in seed/seed-manifest.json - see tools/SeedBuild.kt.
val seedRepo = "https://github.com/ogercyprien/nantes-bus-tram"

tasks.register<JavaExec>("buildSeed") {
    group = "transit"
    description = "Rebuilds the bundled seed from scratch and its manifest (-Posm=extract.osm.pbf [-Pgtfs=zip] [-Pbacs=zip])."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.bustramnantes.transit.tools.SeedBuildKt")
    args(
        "build",
        "root=${rootDir.path}",
        "repo=$seedRepo",
        "osm=${providers.gradleProperty("osm").getOrElse("")}",
        "gtfs=${providers.gradleProperty("gtfs").getOrElse("")}",
        "bacs=${providers.gradleProperty("bacs").getOrElse("")}",
    )
    maxHeapSize = "3g"
}

tasks.register<JavaExec>("verifySeed") {
    group = "transit"
    description = "Rebuilds the seed from the archived inputs of seed/seed-manifest.json and checks it byte for byte."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.bustramnantes.transit.tools.SeedBuildKt")
    args(
        "verify",
        "root=${rootDir.path}",
        "inputs=${providers.gradleProperty("seedInputs").getOrElse("")}",
    )
    maxHeapSize = "3g"
}
