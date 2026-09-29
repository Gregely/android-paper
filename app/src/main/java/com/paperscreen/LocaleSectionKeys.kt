package com.paperscreen

import android.icu.text.AlphabeticIndex
import java.util.Locale

/**
 * Letter headings for the app drawer from ICU's AlphabeticIndex, the way contacts apps do it.
 * It knows the alphabet of the device's language (plus Latin A–Z), so accented names go under
 * their base letter, Cyrillic or Greek names under their own letters, and Chinese or Japanese
 * names under the index the locale defines, instead of each character becoming its own
 * heading. Anything outside the index (digits, symbols, emoji, other scripts) goes under
 * [AppSections.OTHER].
 */
class LocaleSectionKeys(locale: Locale) {

    private val index = AlphabeticIndex<Any>(locale)
        .addLabels(Locale.ENGLISH)
        .buildImmutableIndex()

    fun key(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return AppSections.OTHER
        val bucket = index.getBucket(index.getBucketIndex(trimmed))
        return if (bucket.labelType == AlphabeticIndex.Bucket.LabelType.NORMAL) bucket.label else AppSections.OTHER
    }
}
