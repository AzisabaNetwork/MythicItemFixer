package net.azisaba.mythicitemFixer

import java.util.Locale

/**
 * MMIDs this plugin must leave alone. An entry ending in `*` matches by prefix
 * (`Event_*`); any other entry must match the whole ID. Matching ignores case.
 * Also every ID whose current MythicMobs definition is a plain vanilla item ([vanilla]): giving
 * such an ID to an old item would turn an ordinary item into a Mythic one that no longer stacks
 * with its vanilla counterparts.
 * Immutable, so the async offline scan can share it.
 */
class MmidExclusions private constructor(
    private val exact: Set<String>,
    private val prefixes: List<String>,
    private val vanilla: Set<String> = emptySet(),
) {
    fun isExcluded(mmid: String?): Boolean {
        if (mmid == null) return false
        val id = mmid.trim().lowercase(Locale.ROOT)
        if (id in exact || id in vanilla) return true
        return prefixes.any(id::startsWith)
    }

    /** Whether the ID is left alone because its definition is a plain vanilla item. */
    fun isVanillaDefinition(mmid: String?): Boolean =
        mmid != null && mmid.trim().lowercase(Locale.ROOT) in vanilla

    /** The same exclusions plus these vanilla-definition IDs. */
    fun withVanillaDefinitions(ids: Collection<String>): MmidExclusions =
        MmidExclusions(exact, prefixes, ids.mapTo(HashSet()) { it.trim().lowercase(Locale.ROOT) })

    fun summary(): String =
        "完全一致 ${exact.size}件・前方一致 ${prefixes.size}件・バニラと同じ定義 ${vanilla.size}件"

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
