package com.lattiq.androidbridge

/** Hint-gated OTP extraction. Only treats a number as an OTP if the text smells like one. */
object OtpExtractor {
    private val RE = Regex("\\b(\\d{4,8})\\b")
    private val HINTS = listOf(
        "otp", "code", "verification", "verify", "password",
        "passcode", "2fa", "one-time", "otp:"
    )

    fun extract(title: String?, text: String?): String? {
        val blob = "${title.orEmpty()} ${text.orEmpty()}"
        if (HINTS.none { blob.lowercase().contains(it) }) return null
        return RE.find(blob)?.groupValues?.get(1)
    }
}
