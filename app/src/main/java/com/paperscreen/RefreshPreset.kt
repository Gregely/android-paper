package com.paperscreen

/** Named refresh intervals. [CUSTOM] uses the user's own value instead of [ms]. */
enum class RefreshPreset(val ms: Int?, val label: Int) {
    /** The fastest Android allows: each window can be captured about three times a second. */
    STANDARD(340, R.string.preset_standard),
    READING(500, R.string.preset_reading),
    SLOW(1000, R.string.preset_slow),
    EINK(1500, R.string.preset_eink),
    CUSTOM(null, R.string.preset_custom),
    ;

    companion object {
        val DEFAULT = STANDARD
    }
}
