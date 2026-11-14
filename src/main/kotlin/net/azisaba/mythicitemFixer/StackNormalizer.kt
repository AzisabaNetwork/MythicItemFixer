package net.azisaba.mythicitemFixer

import com.google.gson.JsonElement
import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.persistence.PersistentDataContainerView
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import java.util.LinkedHashSet
import java.util.Locale
import java.util.Optional

/**
 * Makes a Mythic item stack with a freshly generated one (`/mm i get`) when the two differ only in
 * how the same content is encoded: 1.15.2 items converted to 1.21 carry the name and lore as plain
 * strings (no `italic:false`, no colour component) and lack the `custom_data` MythicMobs 5 writes.
 *
 * Only three components can be taken from the current definition:
 * - `custom_name`: when the item has none, or the same text (colours/formatting lost), or the 1.15.2
 *   definition's name. An anvil rename is kept.
 * - `lore`: only when every line has exactly the same text. Lore that differs in text (levels, stats
 *   written in the lore) is kept as is, and the item is not rebuilt at all.
 * - `custom_data`: when it holds nothing but Bukkit PDC, MythicMobs 4 leftovers (`MYTHIC_*`,
 *   `Prevent*`) and keys the current item has too. PDC entries of other plugins are carried over.
 *
 * Every other component (enchantments, damage, attributes, model, skin, ...) stays the item's own.
 * Definitions that generate a different item each time (random stats) are never used.
 */
class StackNormalizer(private val currentItem: (String) -> ItemStack?) {
    private val templates = HashMap<String, Optional<ItemStack>>()
    private val templateKeys = java.util.IdentityHashMap<ItemStack, Optional<Set<String>>>()

    /** Forgets the generated items, for after MythicMobs' definitions were reloaded. */
    fun clearCache() {
        templates.clear()
        templateKeys.clear()
    }

    /**
     * The item rebuilt so it stacks with the current definition's item, or null when nothing would
     * change or it cannot be done safely. [item] itself is not modified.
     */
    fun normalize(item: ItemStack?, id: String?, legacyName: String?): ItemStack? {
        if (item == null || item.type.isAir || id == null) return null
        val template = template(id) ?: return null
        if (template.type != item.type) return null
        if (item.amount > template.maxStackSize) return null
        if (template.isSimilar(item)) return null // already stacks
        val itemId = item.persistentDataContainer.get(ItemConverter.TYPE, PersistentDataType.STRING)
        if (itemId != null && !itemId.trim().equals(id, ignoreCase = true)) return null
        return try {
            if (!customDataReplaceable(item, template)) return null
            // Lore whose text differs is item-specific (Lv1/Lv2, rolled stats, ...). Do not rebuild
            // the item in that case: replacing custom_data would also drop the lore stored there.
            if (!loreReplaceable(item, template)) return null

            val result = template.clone()
            result.copyDataFrom(item) { type -> key(type) !in OWN_COMPONENTS }
            for (type in template.dataTypes) {
                if (key(type) !in OWN_COMPONENTS && !item.hasData(type)) result.unsetData(type)
            }
            if (!nameReplaceable(item, template, legacyName)) copy(item, result, DataComponentTypes.CUSTOM_NAME)
            carryPersistentData(item, template, result)
            result.amount = item.amount
            if (result.isSimilar(item)) null else result
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun template(id: String): ItemStack? {
        val key = id.trim().lowercase(Locale.ROOT)
        var cached = templates[key]
        if (cached == null) {
            cached = Optional.ofNullable(generate(id))
            templates[key] = cached
        }
        return cached.orElse(null)
    }

    private fun generate(id: String): ItemStack? {
        val first = currentItem(id) ?: return null
        val second = currentItem(id) ?: return null
        if (first.type.isAir || !first.isSimilar(second)) return null // random definition: a swap would reroll it
        return first.clone().apply { amount = 1 }
    }

    private fun key(type: DataComponentType): String = type.key.toString()

    private fun <T : Any> copy(from: ItemStack, to: ItemStack, type: DataComponentType.Valued<T>) {
        val value = from.getData(type)
        if (value != null) to.setData(type, value) else to.unsetData(type)
    }

    private fun nameReplaceable(item: ItemStack, template: ItemStack, legacyName: String?): Boolean {
        val have = item.getData(DataComponentTypes.CUSTOM_NAME) ?: return true // the MM name was lost
        val wanted = template.getData(DataComponentTypes.CUSTOM_NAME) ?: return false
        val plain = plain(have)
        return plain == plain(wanted) || legacyName != null && plain == stripLegacy(legacyName)
    }

    private fun loreReplaceable(item: ItemStack, template: ItemStack): Boolean =
        plainLines(item.getData(DataComponentTypes.LORE)) ==
            plainLines(template.getData(DataComponentTypes.LORE))

    private fun plainLines(lore: ItemLore?): List<String> =
        lore?.lines()?.map(::plain) ?: emptyList()

    /**
     * Copies the item's PDC entries the current item does not have (other plugins' data) and drops
     * `mythicmobs:` entries the current definition no longer sets.
     */
    private fun carryPersistentData(item: ItemStack, template: ItemStack, result: ItemStack) {
        val own = item.persistentDataContainer
        if (own.isEmpty) return
        val wanted = template.persistentDataContainer.keys
        result.editPersistentDataContainer { pdc ->
            own.copyTo(pdc, false)
            for (key in ArrayList(pdc.keys)) {
                if (key.namespace == "mythicmobs" && key !in wanted) pdc.remove(key)
            }
        }
    }

    /** True when replacing the item's custom_data with the current one loses nothing but MM data. */
    private fun customDataReplaceable(item: ItemStack, template: ItemStack): Boolean {
        val keys = customDataKeys(item) ?: return false
        if (keys.isEmpty()) return true
        val wanted = templateKeys.getOrPut(template) { Optional.ofNullable(customDataKeys(template)) }
            .orElse(null) ?: return false
        for (key in keys) {
            if (key in wanted || key == "PublicBukkitValues" || key == "display" ||
                key.startsWith("MYTHIC") || key.startsWith("Prevent")
            ) {
                continue
            }
            return false
        }
        return true
    }

    /** Top-level keys of the item's custom_data, empty when it has none, null when unreadable. */
    @Suppress("DEPRECATION")
    private fun customDataKeys(item: ItemStack): Set<String>? {
        val json = Bukkit.getUnsafe().serializeItemAsJson(item)
        val components: JsonElement = json.get("components") ?: return emptySet()
        if (!components.isJsonObject) return emptySet()
        val data = components.asJsonObject.get(CUSTOM_DATA) ?: return emptySet()
        if (data.isJsonNull) return emptySet()
        if (data.isJsonObject) return LinkedHashSet(data.asJsonObject.keySet())
        if (data.isJsonPrimitive) return snbtKeys(data.asString)
        return null
    }

    companion object {
        private const val CUSTOM_DATA = "minecraft:custom_data"
        private const val CUSTOM_NAME = "minecraft:custom_name"
        private const val LORE = "minecraft:lore"
        private val OWN_COMPONENTS = setOf(CUSTOM_DATA, CUSTOM_NAME, LORE)

        /** Top-level keys of an SNBT compound such as `{a:{b:1},"c d":[1,2]}`, or null if malformed. */
        @JvmStatic
        fun snbtKeys(snbt: String): Set<String>? {
            val s = snbt.trim()
            if (s.length < 2 || s[0] != '{' || s[s.length - 1] != '}') return null
            val keys = LinkedHashSet<String>()
            var i = 1
            val end = s.length - 1
            while (true) {
                while (i < end && s[i].isWhitespace()) i++
                if (i >= end) return keys
                val key = StringBuilder()
                var c = s[i]
                if (c == '"' || c == '\'') {
                    i = readQuoted(s, i, key)
                    if (i < 0) return null
                } else {
                    while (i < end && s[i] != ':' && !s[i].isWhitespace()) key.append(s[i++])
                }
                while (i < end && s[i].isWhitespace()) i++
                if (i >= end || s[i] != ':') return null
                keys.add(key.toString())
                i++
                var depth = 0
                while (i < end) {
                    c = s[i]
                    if (c == '"' || c == '\'') {
                        i = readQuoted(s, i, null)
                        if (i < 0) return null
                        continue
                    }
                    if (c == '{' || c == '[') depth++
                    else if (c == '}' || c == ']') depth--
                    else if (c == ',' && depth == 0) break
                    if (depth < 0) return null
                    i++
                }
                if (depth != 0) return null
                if (i < end) i++ // comma
            }
        }

        /** Reads a quoted string starting at [start]; returns the index after its closing quote, or -1. */
        private fun readQuoted(s: String, start: Int, out: StringBuilder?): Int {
            val quote = s[start]
            var i = start + 1
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out?.append(s[i + 1])
                    i += 2
                } else if (c == quote) {
                    return i + 1
                } else {
                    out?.append(c)
                    i++
                }
            }
            return -1
        }

        @JvmStatic
        fun plain(component: Component): String =
            stripLegacy(PlainTextComponentSerializer.plainText().serialize(component))

        @JvmStatic
        fun stripLegacy(text: String?): String {
            val stripped = ChatColor.stripColor(CurrentLegacyDefinitionMatcher.readableText(text))
            return stripped?.trim() ?: ""
        }
    }
}
