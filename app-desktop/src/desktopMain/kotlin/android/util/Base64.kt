package android.util

object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val decoder = if ((flags and URL_SAFE) != 0) {
            java.util.Base64.getUrlDecoder()
        } else {
            java.util.Base64.getDecoder()
        }
        var cleanStr = str.replace("\n", "").replace("\r", "")
        if ((flags and NO_PADDING) == 0 && cleanStr.length % 4 != 0) {
            cleanStr = cleanStr.padEnd(cleanStr.length + (4 - cleanStr.length % 4) % 4, '=')
        }
        return try {
            decoder.decode(cleanStr)
        } catch (e: Exception) {
            try {
                java.util.Base64.getDecoder().decode(cleanStr)
            } catch (e2: Exception) {
                // If it fails, try decoding URL-decoded or cleaning non-base64 characters
                val sanitized = cleanStr.filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' || it == '-' || it == '_' }
                java.util.Base64.getDecoder().decode(sanitized)
            }
        }
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray {
        return decode(String(input), flags)
    }

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray {
        val encoder = if ((flags and URL_SAFE) != 0) {
            java.util.Base64.getUrlEncoder()
        } else {
            java.util.Base64.getEncoder()
        }
        return encoder.encode(input)
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoder = if ((flags and URL_SAFE) != 0) {
            if ((flags and NO_PADDING) != 0) java.util.Base64.getUrlEncoder().withoutPadding() else java.util.Base64.getUrlEncoder()
        } else {
            if ((flags and NO_PADDING) != 0) java.util.Base64.getEncoder().withoutPadding() else java.util.Base64.getEncoder()
        }
        return encoder.encodeToString(input)
    }
}
