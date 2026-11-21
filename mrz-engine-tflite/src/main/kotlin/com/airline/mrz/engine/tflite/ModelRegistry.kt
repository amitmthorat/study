package com.airline.mrz.engine.tflite

import android.content.res.AssetManager
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Thrown when a model listed in the manifest is missing or fails its hash check. */
public class ModelLoadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** One entry of `assets/models.json`. */
public data class ModelSpec(
    val name: String,
    val file: String,
    val version: String,
    val sha256: String,
    val inputShape: IntArray,
    val outputShape: IntArray,
    val temperature: Float,
)

/**
 * Reads the model manifest, loads model bytes from assets into direct buffers and
 * verifies their SHA-256. Model versions are reported independently of the SDK version.
 */
public class ModelRegistry(private val assets: AssetManager, manifestPath: String = "models.json") {

    public val specs: Map<String, ModelSpec>

    init {
        val json = assets.open(manifestPath).bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val models = root.getJSONArray("models")
        val out = LinkedHashMap<String, ModelSpec>()
        for (i in 0 until models.length()) {
            val m = models.getJSONObject(i)
            out[m.getString("name")] = ModelSpec(
                name = m.getString("name"),
                file = m.getString("file"),
                version = m.getString("version"),
                sha256 = m.getString("sha256"),
                inputShape = m.getJSONObject("input").getJSONArray("shape").let { a -> IntArray(a.length()) { a.getInt(it) } },
                outputShape = m.getJSONObject("output").getJSONArray("shape").let { a -> IntArray(a.length()) { a.getInt(it) } },
                temperature = m.optDouble("temperature", 1.0).toFloat(),
            )
        }
        specs = out
    }

    /** Human-readable version string for diagnostics, e.g. `mrz_detector=1,mrz_recognizer=1`. */
    public val versionString: String get() = specs.values.joinToString(",") { "${it.name}=${it.version}" }

    public fun load(name: String): ByteBuffer {
        val spec = specs[name] ?: throw ModelLoadException("model '$name' is not in the manifest")
        val bytes = try {
            assets.open(spec.file).use { it.readBytes() }
        } catch (e: java.io.IOException) {
            throw ModelLoadException("model file '${spec.file}' for '$name' is missing from assets", e)
        }
        if (spec.sha256 != "TBD") {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                throw ModelLoadException("model '$name' hash mismatch: expected ${spec.sha256}, got $actual")
            }
        }
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
    }
}
