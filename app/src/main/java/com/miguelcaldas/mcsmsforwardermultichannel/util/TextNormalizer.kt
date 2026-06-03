package com.miguelcaldas.mcsmsforwardermultichannel.util

import java.text.Normalizer
import java.util.Locale

object TextNormalizer {
    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    // Normalize text for regex matching: strip combining diacritics (NFD then drop
    // Unicode category Mn) and lowercase using Locale.ROOT for deterministic folding,
    // so user-supplied patterns can be written diacritic-free and case-free.
    fun normalizeForMatching(text: String): String =
        COMBINING_MARKS.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
}
