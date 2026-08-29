plugins {
    alias(libs.plugins.kotlin.jvm)
}

/*
 * Pure-JVM transit core (no Android dependency): GTFS parsing, the packed network format, the
 * in-memory TransitNetwork, the RAPTOR router and departure-board composition. Pure JVM so that
 *  - the very same converter runs at build time (`./gradlew :transit:packSeed`, which writes the
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
    // Only the seed tool (tools/PackSeed.kt) parses JSON; the runtime core is dependency-free.
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

tasks.register<JavaExec>("packSeed") {
    group = "transit"
    description = "Regenerates app/src/main/assets/seed/network.bin from the current GTFS (or -Pgtfs=zip)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.bustramnantes.transit.tools.PackSeedKt")
    args(
        rootProject.file("app/src/main/assets/seed/network.bin").path,
        file("seed/logical_stops.geojson").path,
        providers.gradleProperty("gtfs").getOrElse(""),
        rootProject.file("app/src/main/assets/seed/walk_graph.bin").path,
    )
    maxHeapSize = "1g"
}

tasks.register<JavaExec>("packWalkGraph") {
    group = "transit"
    description = "Regenerates app/src/main/assets/seed/walk_graph.bin from a Geofabrik extract (-Posm=/path.osm.pbf)."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.bustramnantes.transit.tools.PackWalkGraphKt")
    args(
        rootProject.file("app/src/main/assets/seed/walk_graph.bin").path,
        providers.gradleProperty("osm").getOrElse(""),
    )
    maxHeapSize = "3g"
}
