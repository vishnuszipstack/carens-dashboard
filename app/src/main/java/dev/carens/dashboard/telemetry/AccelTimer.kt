package dev.carens.dashboard.telemetry

/**
 * Passive 0–60 / 0–100 km/h timer. It never prompts the driver; it just
 * notices when the car pulls away from a stop and reaches the targets in one
 * continuous run. GPS gives ~1 fix per second, so crossing times are
 * interpolated between fixes and are approximate.
 */
class AccelTimer(private val targetsKmh: List<Int> = listOf(60, 100)) {
    data class Result(val targetKmh: Int, val ms: Long)

    private var startMs: Long? = null
    private var prevT: Long? = null
    private var prevV: Double? = null
    private val reached = mutableSetOf<Int>()

    fun reset() {
        startMs = null
        prevT = null
        prevV = null
        reached.clear()
    }

    fun onSample(timeMs: Long, speedKmh: Double?, reliable: Boolean): List<Result> {
        if (!reliable || speedKmh == null) {
            reset()
            return emptyList()
        }
        val results = mutableListOf<Result>()
        val pt = prevT
        val pv = prevV
        if (speedKmh < STOPPED_KMH) {
            startMs = timeMs
            reached.clear()
        } else {
            val s = startMs
            if (s != null && pt != null && pv != null) {
                val aborted = timeMs - s > MAX_RUN_MS || speedKmh < pv - 8 || timeMs - pt > 3_000
                if (aborted) {
                    startMs = null
                } else {
                    for (target in targetsKmh) {
                        if (target in reached) continue
                        if (pv < target && speedKmh >= target && speedKmh > pv) {
                            val frac = (target - pv) / (speedKmh - pv)
                            val crossAt = pt + (frac * (timeMs - pt)).toLong()
                            results += Result(target, crossAt - s)
                            reached += target
                        }
                    }
                    if (reached.size == targetsKmh.size) startMs = null
                }
            }
        }
        prevT = timeMs
        prevV = speedKmh
        return results
    }

    private companion object {
        const val STOPPED_KMH = 2.0
        const val MAX_RUN_MS = 40_000L
    }
}
