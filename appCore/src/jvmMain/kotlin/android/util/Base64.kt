package android.util

/**
 * Desktop stand-in for android.util.Base64. Without it extensions got a do-nothing placeholder
 * that returned null: Cricify's live channels lost their ClearKey keys that way and stayed black.
 * Same flags and output as Android: lines of 76 characters ending in '\n' unless NO_WRAP.
 */
object Base64 {
    const val DEFAULT = 0
    const val NO_PADDING = 1
    const val NO_WRAP = 2
    const val CRLF = 4
    const val URL_SAFE = 8
    const val NO_CLOSE = 16

    @JvmStatic
    fun encode(input: ByteArray, flags: Int): ByteArray = encodeToString(input, flags).toByteArray(Charsets.US_ASCII)

    @JvmStatic
    fun encode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray = encode(input.copyOfRange(offset, offset + len), flags)

    @JvmStatic
    fun encodeToString(input: ByteArray, flags: Int): String {
        val encoder = if (flags and URL_SAFE != 0) java.util.Base64.getUrlEncoder() else java.util.Base64.getEncoder()
        var text = (if (flags and NO_PADDING != 0) encoder.withoutPadding() else encoder).encodeToString(input)
        if (flags and NO_WRAP == 0 && text.isNotEmpty()) {
            val newline = if (flags and CRLF != 0) "\r\n" else "\n"
            text = text.chunked(76).joinToString(newline, postfix = newline)
        }
        return text
    }

    @JvmStatic
    fun encodeToString(input: ByteArray, offset: Int, len: Int, flags: Int): String = encodeToString(input.copyOfRange(offset, offset + len), flags)

    /** Like Android: line breaks and spaces are skipped, padding is optional, either alphabet is read. */
    @JvmStatic
    fun decode(str: String, flags: Int): ByteArray {
        val clean = str.filterNot { it.isWhitespace() }.trimEnd('=').replace('+', '-').replace('/', '_')
        return try {
            java.util.Base64.getUrlDecoder().decode(clean)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("bad base-64", e)
        }
    }

    @JvmStatic
    fun decode(input: ByteArray, flags: Int): ByteArray = decode(String(input, Charsets.US_ASCII), flags)

    @JvmStatic
    fun decode(input: ByteArray, offset: Int, len: Int, flags: Int): ByteArray = decode(input.copyOfRange(offset, offset + len), flags)
}
