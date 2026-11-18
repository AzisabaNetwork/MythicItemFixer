package net.azisaba.mythicitemFixer

import com.google.gson.JsonElement
import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.datacomponent.item.ResolvableProfile
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
 * strings (no `italic:false`, no colour component), lack the `custom_data` MythicMobs 5 writes, and
 * often carry `tooltip_display` / model / attribute components written differently.
 *
 * Taken from the current definition:
 * - `custom_name`: when the item has none, or the same text (colours/formatting lost), or the 1.15.2
 *   definition's name. An anvil rename is kept.
 * - `lore`: when every line has the same text as the current or the 1.15.2 definition. Lore that
 *   differs otherwise (levels, stats written in the lore) means the item is not rebuilt at all.
 * - `custom_data`: when it holds nothing but Bukkit PDC, MythicMobs 4 leftovers (`MYTHIC_*`,
 *   `Prevent*`) and keys the current item has too. PDC entries of other plugins are carried over.
 * - `profile` (player heads): when the skin textures are identical and only the UUID/name differs.
 * - For items that stack (max stack size > 1) also the components only the definition sets
 *   ([DEFINITION_COMPONENTS]: tooltip_display, model, attributes, unbreakable, ...).
 *
 * Everything a player can change on one item (enchantments, damage, repair cost, trims, contents,
 * ...) stays the item's own. Definitions that generate a different item each time (random stats)
 * are never used.
 */
class StackNormalizer(private val currentItem: (String) -> ItemStack?) {
    private val templates = HashMap<String, Optional<ItemStack>>()
    private val templateProblems = HashMap<String, String>()
    private val templateKeys = java.util.IdentityHashMap<ItemStack, Optional<Set<String>>>()

    /** Forgets the generated items, for after MythicMobs' definitions were reloaded. */
    fun clearCache() {
        templates.clear()
        templateProblems.clear()
        templateKeys.clear()
    }

    /**
     * What [analyze] found: [result] is the rebuilt item (null when nothing changes or it cannot be
     * done safely), [stacks] whether the item (rebuilt or not) now stacks with `/mm i get`, and
     * [reasons] why it does not (only for items that can stack at all).
     */
    class Analysis(
        val result: ItemStack?,
        val stacks: Boolean,
        val reasons: List<String>,
        /** False for items that never stack (max stack size 1), where [stacks] means nothing. */
        val canStack: Boolean = true,
    )

    /**
     * The item rebuilt so it stacks with the current definition's item, or null when nothing would
     * change or it cannot be done safely. [item] itself is not modified.
     */
    fun normalize(item: ItemStack?, id: String?, legacyName: String?, legacyLore: List<String>? = null): ItemStack? =
        analyze(item, id, legacyName, legacyLore).result

    fun analyze(item: ItemStack?, id: String?, legacyName: String?, legacyLore: List<String>? = null): Analysis {
        if (item == null || item.type.isAir || id == null) return Analysis(null, false, emptyList(), false)
        val template = template(id) ?: run {
            val stackable = item.maxStackSize > 1
            val reason = templateProblems[normalizeId(id)] ?: "今のMMの定義を生成できません"
            return Analysis(null, false, if (stackable) listOf(reason) else emptyList(), stackable)
        }
        val canStack = template.maxStackSize > 1
        fun refuse(reason: String) = Analysis(null, false, if (canStack) listOf(reason) else emptyList(), canStack)
        if (template.type != item.type) return refuse("素材が違います（今の定義は ${template.type}）")
        if (item.amount > template.maxStackSize) return refuse("個数が今の定義の最大スタック数を超えています")
        if (template.isSimilar(item)) return Analysis(null, true, emptyList(), canStack) // already stacks
        val itemId = item.persistentDataContainer.get(ItemConverter.TYPE, PersistentDataType.STRING)
        if (itemId != null && !itemId.trim().equals(id, ignoreCase = true)) return refuse("MMIDが違います")
        return try {
            val foreign = foreignCustomData(item, template)
            if (foreign == null) return refuse("custom_dataを読めません")
            if (foreign.isNotEmpty()) return refuse("他のプラグインのデータがあります（${foreign.joinToString()}）")
            // Lore whose text differs is item-specific (Lv1/Lv2, rolled stats, ...). Do not rebuild
            // the item in that case: replacing custom_data would also drop the lore stored there.
            if (!loreReplaceable(item, template, legacyLore)) return refuse("Loreの文章が今の定義と違います")

            val sameSkin = sameSkin(item, template)
            // Modifiers that differ only in their ids (MythicMobs rolls them per item, 1.15.2 items keep
            // their old UUIDs) are the same attributes, so even unstackable items take the current ids.
            val sameAttributes = sameAttributesIgnoringIds(item, template)
            val fromTemplate = { k: String ->
                k in OWN_COMPONENTS || k == PROFILE && sameSkin || k == ATTRIBUTE_MODIFIERS && sameAttributes ||
                    canStack && k in DEFINITION_COMPONENTS
            }
            val result = template.clone()
            result.copyDataFrom(item) { type -> !fromTemplate(key(type)) }
            for (type in template.dataTypes) {
                if (!fromTemplate(key(type)) && !item.hasData(type)) result.unsetData(type)
            }
            if (!nameReplaceable(item, template, legacyName)) copy(item, result, DataComponentTypes.CUSTOM_NAME)
            carryPersistentData(item, template, result)
            result.amount = item.amount
            val changed = !result.isSimilar(item)
            val final = if (changed) result else item
            if (!canStack || final.isSimilar(template)) {
                Analysis(if (changed) result else null, canStack, emptyList(), canStack)
            } else {
                val reasons = differingComponents(final, template).map { "${label(it)}が違います" }
                Analysis(if (changed) result else null, false, reasons.ifEmpty { listOf("PDCが違います") })
            }
        } catch (error: RuntimeException) {
            refuse("確認中にエラー：${error.javaClass.simpleName}")
        }
    }

    private fun normalizeId(id: String): String = id.trim().lowercase(Locale.ROOT)

    /** The item `/mm i get` gives for [id] (cached), or null when it is missing or random. */
    fun template(id: String): ItemStack? {
        val key = normalizeId(id)
        var cached = templates[key]
        if (cached == null) {
            cached = Optional.ofNullable(generate(id))
            templates[key] = cached
        }
        return cached.orElse(null)
    }

    private fun generate(id: String): ItemStack? {
        val first = currentItem(id)
        val second = currentItem(id)
        if (first == null || second == null || first.type.isAir) {
            templateProblems[normalizeId(id)] = "今のMMにこのIDの定義がありません"
            return null
        }
        if (!first.isSimilar(second)) {
            // A head whose profile UUID is generated per item is still one definition when the skin
            // is the same, and so are attribute modifiers whose ids MythicMobs rolls per item; anything
            // else differing is a random definition and a swap would reroll it.
            val aligned = second.clone()
            if (sameSkin(first, second)) copy(first, aligned, DataComponentTypes.PROFILE)
            if (sameAttributesIgnoringIds(first, second)) copy(first, aligned, DataComponentTypes.ATTRIBUTE_MODIFIERS)
            if (!first.isSimilar(aligned)) {
                val random = differingComponents(first, aligned).joinToString { label(it) }
                templateProblems[normalizeId(id)] =
                    "今の定義は毎回違うアイテムを生成します（${random.ifEmpty { "ランダム要素" }}）"
                return null
            }
        }
        return first.clone().apply { amount = 1 }
    }

    /** True when both items are heads with the same texture properties (UUID and name may differ). */
    private fun sameSkin(a: ItemStack, b: ItemStack): Boolean {
        val skin = skin(a.getData(DataComponentTypes.PROFILE) ?: return false)
        return skin.isNotEmpty() && skin == skin(b.getData(DataComponentTypes.PROFILE) ?: return false)
    }

    /** True when both items' attribute modifiers are the same apart from the modifier ids. */
    private fun sameAttributesIgnoringIds(a: ItemStack, b: ItemStack): Boolean {
        val ca = components(a)[ATTRIBUTE_MODIFIERS] ?: return false
        val cb = components(b)[ATTRIBUTE_MODIFIERS] ?: return false
        return withoutIds(ca) == withoutIds(cb)
    }

    /** A copy of [json] with every `id` field of the modifiers removed. */
    private fun withoutIds(json: JsonElement): JsonElement {
        val copy = json.deepCopy()
        fun strip(element: JsonElement) {
            when {
                element.isJsonObject -> {
                    val obj = element.asJsonObject
                    obj.remove("id")
                    for ((_, value) in obj.entrySet()) strip(value)
                }
                element.isJsonArray -> element.asJsonArray.forEach(::strip)
            }
        }
        strip(copy)
        return copy
    }

    private fun skin(profile: ResolvableProfile): Set<Pair<String, String>> =
        profile.properties().mapTo(HashSet()) { it.name to it.value }

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

    private fun loreReplaceable(item: ItemStack, template: ItemStack, legacyLore: List<String>?): Boolean {
        val have = plainLines(item.getData(DataComponentTypes.LORE))
        return have == plainLines(template.getData(DataComponentTypes.LORE)) ||
            legacyLore != null && have == legacyLore.map(::stripLegacy)
    }

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

    /**
     * Top-level custom_data keys that replacing it with the current one would lose (other plugins'
     * data), empty when only MM data / vanilla leftovers are there, null when unreadable.
     */
    private fun foreignCustomData(item: ItemStack, template: ItemStack): List<String>? {
        val keys = customDataKeys(item) ?: return null
        if (keys.isEmpty()) return emptyList()
        val wanted = templateKeys.getOrPut(template) { Optional.ofNullable(customDataKeys(template)) }
            .orElse(null) ?: return null
        return keys.filter { key ->
            key !in wanted && key !in REPLACEABLE_CUSTOM_DATA &&
                !key.startsWith("MYTHIC") && !key.startsWith("Prevent")
        }
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
        private const val PROFILE = "minecraft:profile"
        private const val ATTRIBUTE_MODIFIERS = "minecraft:attribute_modifiers"
        private val OWN_COMPONENTS = setOf(CUSTOM_DATA, CUSTOM_NAME, LORE)

        /**
         * custom_data keys that are safe to drop: Bukkit PDC (carried over separately) and 1.15.2 tags
         * the data fixer already turned into components, left behind as dead copies.
         */
        private val REPLACEABLE_CUSTOM_DATA = setOf(
            "PublicBukkitValues", "display", "HideFlags", "Unbreakable", "CustomModelData",
            "AttributeModifiers", "Enchantments", "SkullOwner", "CanDestroy", "CanPlaceOn",
        )

        /**
         * Components only the MM definition sets, never a player on one item. For stackable items they
         * are taken from the current definition so old encodings (HideFlags → tooltip_display, old
         * models, attribute modifiers written by MM4) do not keep the stacks apart.
         */
        private val DEFINITION_COMPONENTS = setOf(
            "minecraft:tooltip_display", "minecraft:custom_model_data", "minecraft:item_model",
            "minecraft:unbreakable", "minecraft:attribute_modifiers", "minecraft:enchantment_glint_override",
            "minecraft:rarity", "minecraft:item_name", "minecraft:max_stack_size", "minecraft:max_damage",
            "minecraft:food", "minecraft:consumable", "minecraft:use_remainder", "minecraft:use_cooldown",
            "minecraft:tooltip_style", "minecraft:can_break", "minecraft:can_place_on",
            "minecraft:damage_resistant", "minecraft:equippable", "minecraft:glider",
            "minecraft:break_sound", "minecraft:weapon", "minecraft:tool", "minecraft:repairable",
            "minecraft:enchantable", "minecraft:hide_tooltip", "minecraft:hide_additional_tooltip",
        )

        private val LABELS = mapOf(
            CUSTOM_DATA to "MMデータ(custom_data)", CUSTOM_NAME to "名前", LORE to "Lore",
            PROFILE to "ヘッドのスキン", "minecraft:enchantments" to "エンチャント",
            "minecraft:stored_enchantments" to "本のエンチャント", "minecraft:damage" to "耐久値",
            "minecraft:repair_cost" to "金床の使用歴", "minecraft:trim" to "装飾(trim)",
            "minecraft:dyed_color" to "染色", "minecraft:tooltip_display" to "表示設定(HideFlags)",
            "minecraft:custom_model_data" to "Model", "minecraft:item_model" to "ItemModel",
            "minecraft:attribute_modifiers" to "属性", "minecraft:unbreakable" to "耐久無限",
            "minecraft:enchantment_glint_override" to "エンチャントの光", "minecraft:container" to "中身",
            "minecraft:bundle_contents" to "バンドルの中身", "minecraft:potion_contents" to "ポーション効果",
        )

        /**
         * True when the item is a plain vanilla item apart from MythicMobs' own tags: no component
         * differs from `ItemStack(type)` except a custom_data holding nothing but `mythicmobs:` PDC and
         * MythicMobs 4 leftovers (`MYTHIC_*`, `Prevent*`).
         */
        @JvmStatic
        fun isVanillaEquivalent(item: ItemStack): Boolean {
            if (item.type.isAir) return false
            val vanilla = ItemStack(item.type)
            for (key in differingComponents(item, vanilla)) {
                if (key != CUSTOM_DATA) return false
                val data = components(item)[CUSTOM_DATA] ?: continue
                val keys = when {
                    data.isJsonObject -> data.asJsonObject.keySet()
                    data.isJsonPrimitive -> snbtKeys(data.asString) ?: return false
                    else -> return false
                }
                if (keys.any { it != "PublicBukkitValues" && !it.startsWith("MYTHIC") && !it.startsWith("Prevent") }) {
                    return false
                }
                if (item.persistentDataContainer.keys.any { it.namespace != "mythicmobs" }) return false
            }
            return true
        }

        /** A short Japanese name for a component key such as `minecraft:lore`. */
        @JvmStatic
        fun label(key: String): String = LABELS[key] ?: key.removePrefix("minecraft:")

        /** Component keys whose value differs between the two items (count is ignored). */
        @Suppress("DEPRECATION")
        @JvmStatic
        fun differingComponents(a: ItemStack, b: ItemStack): List<String> {
            val ca = components(a)
            val cb = components(b)
            val keys = LinkedHashSet<String>()
            keys.addAll(ca.keys)
            keys.addAll(cb.keys)
            return keys.filter { ca[it] != cb[it] }
        }

        /** The item's components as JSON, keyed like `minecraft:lore` (count ignored). */
        @Suppress("DEPRECATION")
        @JvmStatic
        fun components(item: ItemStack): Map<String, JsonElement> {
            val json = Bukkit.getUnsafe().serializeItemAsJson(item.clone().apply { amount = 1 })
            val components = json.get("components")
            if (components == null || !components.isJsonObject) return emptyMap()
            return components.asJsonObject.entrySet().associate { it.key to it.value }
        }

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
