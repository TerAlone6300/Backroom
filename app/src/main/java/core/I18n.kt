package com.backrooms.game
// I18n.kt - English / Vietnamese. The English text itself is the key: T("PLAY") returns the Vietnamese text from VI_STRINGS (Lang_vi.kt)
// when the language is Vietnamese and that entry is filled in; a blank or missing entry falls back to the English text.
// Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

object I18n {
    const val EN = "en"; const val VI = "vi"
    @Volatile var lang = EN
    val isVi get() = lang == VI
    fun toggle() { lang = if (lang == VI) EN else VI }
    /** Name of the current language, written in that language. */
    fun langName() = if (isVi) "Tiếng Việt" else "English"
    /** First run: follow the phone / computer language. */
    fun systemLang() = if (java.util.Locale.getDefault().language == "vi") VI else EN
    fun fromCode(c: String?) = if (c == VI) VI else EN
    fun tr(s: String): String {
        if (lang != VI) return s
        val t = VI_STRINGS[s]
        return if (t.isNullOrBlank()) s else t
    }
}

/** Translate a fixed English text. Anything that is not in the table (numbers, names, already translated text) comes back unchanged. */
fun T(s: String): String = I18n.tr(s)
/** Translate a text with %d / %s placeholders, then fill it in: Tf("%d / %d  ·  click to play", a, b). Keep the placeholders in the translation. */
fun Tf(pattern: String, vararg a: Any): String = String.format(I18n.tr(pattern), *a)
