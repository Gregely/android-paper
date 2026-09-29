package com.paperscreen

/** Named refresh intervals. [CUSTOM] uses the user's own value instead of [ms]. */
enum class RefreshPreset(val ms: Int?, val label: Int) {
    REALTIME(0, R.string.preset_realtime),
    FAST(100, R.string.preset_fast),
    RESPONSIVE(250, R.string.preset_responsive),
    READING(500, R.string.preset_reading),
    SLOW(1000, R.string.preset_slow),
    EINK(1500, R.string.preset_eink),
    CUSTOM(null, R.string.preset_custom),
    ;

    companion object {
        val DEFAULT = RESPONSIVE
    }
}
