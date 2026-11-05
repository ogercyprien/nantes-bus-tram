package com.bustramnantes.transit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * The committed seed files are the ones `transit/seed/seed-manifest.json` records (built from its
 * inputs by `:transit:buildSeed`, checked end to end by `:transit:verifySeed`): a seed regenerated
 * without rewriting the manifest, or edited by hand, fails here.
 */
class SeedManifestTest {
    private val root = File("..")
    private val manifest = Json.parseToJsonElement(File(root, "transit/seed/seed-manifest.json").readText()).jsonObject

    @Test
    fun `committed seed files match the manifest`() {
        for (output in manifest["outputs"]!!.jsonArray.map { it.jsonObject }) {
            val path = output["file"]!!.jsonPrimitive.content
            assertEquals(path, output["sha256"]!!.jsonPrimitive.content, sha256(File(root, path)))
        }
    }

    @Test
    fun `in-repository inputs match the manifest`() {
        for (input in manifest["inputs"]!!.jsonArray.map { it.jsonObject }.filter { it["archived"]!!.jsonPrimitive.content == "false" }) {
            val path = input["file"]!!.jsonPrimitive.content
            assertEquals(path, input["sha256"]!!.jsonPrimitive.content, sha256(File(root, path)))
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
