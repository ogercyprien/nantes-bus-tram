package com.bustramnantes.transit.tools

import com.bustramnantes.transit.NetworkFormat
import com.bustramnantes.transit.gtfs.GtfsConverter
import com.bustramnantes.transit.gtfs.GtfsSource
import java.io.File

/**
 * Times a full GTFS zip -> packed network conversion on the JVM and reports peak
 * heap. `./gradlew :transit:convertBenchmark -Pgtfs=/path/gtfs.zip`
 */
fun main(args: Array<String>) {
    val zip = File(args[0])
    val out = File(args.getOrElse(1) { "build/network.bin" })
    val rt = Runtime.getRuntime()
    var peak = 0L
    val sampler = Thread {
        while (!Thread.currentThread().isInterrupted) {
            peak = maxOf(peak, rt.totalMemory() - rt.freeMemory())
            try { Thread.sleep(20) } catch (e: InterruptedException) { break }
        }
    }.apply { isDaemon = true; start() }
    val t0 = System.nanoTime()
    val network = GtfsConverter(GtfsSource.zip(zip), feedId = "bench").convert()
    val t1 = System.nanoTime()
    out.parentFile?.mkdirs()
    out.outputStream().use { NetworkFormat.write(network, it) }
    val t2 = System.nanoTime()
    val back = out.inputStream().use { NetworkFormat.read(it) }
    val t3 = System.nanoTime()
    sampler.interrupt()
    println("convert ${(t1 - t0) / 1_000_000} ms, write ${(t2 - t1) / 1_000_000} ms, read ${(t3 - t2) / 1_000_000} ms")
    println("peak heap ~${peak / (1 shl 20)} MB (max ${rt.maxMemory() / (1 shl 20)} MB)")
    println("file ${out.length() / 1024} KB")
    println(
        "routes=${back.routes.size} stopPlaces=${back.stopPlaces.size} quays=${back.quayCount} " +
            "patterns=${back.patterns.size} trips=${back.tripCount} profiles=${back.profileArrivals.size} " +
            "serviceSets=${back.serviceSets.size} vjPairs=${back.vjNumbers.size} shapes=${back.shapes.size} " +
            "valid ${back.validFrom}..${back.validUntil}",
    )
}
