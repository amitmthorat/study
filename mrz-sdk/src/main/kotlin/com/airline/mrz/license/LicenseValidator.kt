package com.airline.mrz.license

import android.util.Base64
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.LocalDate

/**
 * Offline license check.
 *
 * A license is a JSON document `{"payload": base64, "signature": base64}` where the
 * payload is JSON `{"applicationId": ..., "expires": "YYYY-MM-DD", "features": [...]}`
 * signed with ECDSA P-256 over SHA-256 (available on every supported Android version
 * without extra libraries). The embedded public key must be replaced with the real
 * one before the first customer build.
 */
public class LicenseValidator(
    private val publicKeyBase64: String = EMBEDDED_PUBLIC_KEY,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    public sealed interface Result {
        public data class Valid(val applicationId: String, val expires: LocalDate, val features: Set<String>) : Result
        public data class Invalid(val reason: String) : Result
    }

    public fun validate(licenseJson: String, applicationId: String): Result {
        val root = try {
            JSONObject(licenseJson)
        } catch (e: Exception) {
            return Result.Invalid("malformed license")
        }
        val payload = root.optString("payload", null) ?: return Result.Invalid("missing payload")
        val signature = root.optString("signature", null) ?: return Result.Invalid("missing signature")
        val payloadBytes = try {
            Base64.decode(payload, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            return Result.Invalid("payload is not base64")
        }
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(publicKey())
                update(payloadBytes)
                verify(Base64.decode(signature, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            false
        }
        if (!ok) return Result.Invalid("signature check failed")

        val body = JSONObject(String(payloadBytes, Charsets.UTF_8))
        val licensedApp = body.optString("applicationId")
        if (licensedApp != applicationId) return Result.Invalid("license is for '$licensedApp', not '$applicationId'")
        val expires = try {
            LocalDate.parse(body.getString("expires"))
        } catch (e: Exception) {
            return Result.Invalid("bad expiry date")
        }
        if (today().isAfter(expires)) return Result.Invalid("license expired on $expires")
        val features = body.optJSONArray("features")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        return Result.Valid(licensedApp, expires, features)
    }

    private fun publicKey(): PublicKey =
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.decode(publicKeyBase64, Base64.DEFAULT)))

    public companion object {
        /** Placeholder. Generate a real P-256 key pair for release and keep the private key offline. */
        public const val EMBEDDED_PUBLIC_KEY: String = "REPLACE_WITH_BASE64_X509_P256_PUBLIC_KEY"
    }
}
