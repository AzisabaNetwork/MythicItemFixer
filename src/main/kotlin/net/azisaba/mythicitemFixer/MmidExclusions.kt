package net.azisaba.mythicitemFixer

import java.util.Locale

/**
 * MMIDs this plugin must leave alone. An entry ending in `*` matches by prefix
 * (`Event_*`); any other entry must match the whole ID. Matching ignores case.
 * Immutable, so the async offline scan can share it.
 */
class MmidExclusions private constructor(
    private val exact: Set<String>,
    private val prefixes: List<String>,
) {
    fun isExcluded(mmid: String?): Boolean {
        if (mmid == null) return false
        val id = mmid.trim().lowercase(Locale.ROOT)
        if (id in exact) return true
        return prefixes.any(id::startsWith)
    }

    fun summary(): String = "完全一致 ${exact.size}件・前方一致 ${prefixes.size}件"

    companion object {
        @JvmField
        val NONE = MmidExclusions(emptySet(), emptyList())

        @JvmStatic
        fun parse(entries: List<String?>): MmidExclusions {
            val exact = HashSet<String>()
            val prefixes = ArrayList<String>()
            for (raw in entries) {
                val entry = raw?.trim()?.lowercase(Locale.ROOT) ?: continue
                if (entry.isEmpty()) continue
                if (entry.endsWith("*")) {
                    val prefix = entry.dropLast(1)
                    if (prefix.isNotEmpty()) prefixes.add(prefix)
                } else {
                    exact.add(entry)
                }
            }
            return MmidExclusions(exact.toSet(), prefixes.toList())
        }
    }
}
