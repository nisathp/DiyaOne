package com.naminfo.translation

enum class CallLanguage(
    val code: String?,
    val label: String
) {
    NONE(null, "None"),
    TAMIL("ta", "Tamil"),
    ENGLISH("en", "English"),
    TELUGU("te", "Telugu"),
    HINDI("hi", "Hindi"),
    ARABIC("ar", "Arabic"),
    SPANISH("es", "Spanish"),
    CHINESE("zh", "Chinese");

    companion object {
        fun fromCode(code: String?): CallLanguage {
            if (code.isNullOrEmpty()) return NONE
            val trimmed = code.trim()
            return entries.find {
                it.name.equals(trimmed, ignoreCase = true) ||
                        it.label.equals(trimmed, ignoreCase = true) ||
                        (it.code != null && it.code.equals(trimmed, ignoreCase = true))
            } ?: NONE
        }
    }
}
