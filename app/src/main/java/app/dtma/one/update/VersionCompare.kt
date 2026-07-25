package app.dtma.one.update

/**
 * Compare dotted version strings (optional leading "v", ignores -suffix like -debug).
 * Returns >0 if [remote] is newer than [local].
 */
object VersionCompare {

    fun isNewer(remote: String, local: String): Boolean =
        compare(remote, local) > 0

    fun compare(a: String, b: String): Int {
        val pa = parse(a)
        val pb = parse(b)
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("v", ignoreCase = true) && s.length > 1 && s[1].isDigit()) {
            s = s.substring(1)
        }
        // Drop pre-release / build / product suffixes: 0.2.3-debug → 0.2.3
        s = s.substringBefore('-').substringBefore('+')
        return s
    }

    private fun parse(raw: String): List<Int> =
        normalize(raw).split('.').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
        }
}
