package zed.rainxch.githubstore.util

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object PrivacyHash {
    private val pepperBytes: ByteArray = (System.getenv("DEVICE_ID_PEPPER")
        ?.takeIf { it.isNotBlank() }
        ?: "dev-only-pepper-do-not-use-in-prod")
        .toByteArray(StandardCharsets.UTF_8)

    // Mac instances are not thread-safe but are expensive to construct, so we
    // hand each thread its own pre-keyed instance and reset() between calls.
    // Same pattern Java code uses for MessageDigest.
    private val mac: ThreadLocal<Mac> = ThreadLocal.withInitial {
        Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(pepperBytes, "HmacSHA256"))
        }
    }

    fun hash(value: String): String {
        if (value.isEmpty()) return ""
        val m = mac.get()
        m.reset()
        return m.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
