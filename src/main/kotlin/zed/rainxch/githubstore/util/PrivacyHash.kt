package zed.rainxch.githubstore.util

import java.security.MessageDigest

object PrivacyHash {
    private val pepper: String = System.getenv("DEVICE_ID_PEPPER")
        ?.takeIf { it.isNotBlank() }
        ?: "dev-only-pepper-do-not-use-in-prod"

    fun hash(value: String): String {
        if (value.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(pepper.toByteArray(Charsets.UTF_8))
        digest.update(value.toByteArray(Charsets.UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
