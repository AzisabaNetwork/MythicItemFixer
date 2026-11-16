package net.azisaba.mythicitemFixer

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.md_5.bungee.api.chat.BaseComponent
import net.md_5.bungee.chat.ComponentSerializer
import org.bukkit.ChatColor
import org.bukkit.Color
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.LeatherArmorMeta
import org.bukkit.inventory.meta.SkullMeta
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.IOException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Base64
import java.util.Collections

/**
 * Finds the MythicMobs 4 definition of an item whose ID was lost before the move to 1.21, by
 * comparing it with the old `Items` YAML: material, display name, lore and head skin.
 *
 * The material is taken from the item the current MythicMobs generates for that ID, so old numeric
 * IDs (`Id: 397` + `Data: 3`) and materials renamed since 1.15 still match. Definitions whose ID no
 * longer exists in MythicMobs are skipped: they could not be repaired anyway.
 *
 * A match is then checked against what the definition fixes and nothing else would set: an item
 * with another custom model, a different leather colour, or not unbreakable when the definition is,
 * is not that Mythic item even if its name and lore are the same.
 */
class CurrentLegacyDefinitionMatcher private constructor() {
    private val ids = HashMap<Signature, String>()
    private val plainIds = HashMap<Signature, String>()
    private val skinlessHeadIds = HashMap<Signature, String>()
    private val traitsById = HashMap<String, Traits>()
    private val headSkinsById = HashMap<String, String>()
    private val legacyNamesById = HashMap<String, String>()
    private val legacyLoresById = HashMap<String, List<String>>()
    private val ambiguous = HashSet<Signature>()
    /** Plain signatures of what the current MythicMobs generates (`/mm i get`), the fallback. */
    private val currentIds = HashMap<Signature, String>()
    private val currentAmbiguous = HashSet<Signature>()
    private val plainAmbiguous = HashSet<Signature>()
    private val skinlessHeadAmbiguous = HashSet<Signature>()
    private var files = 0
    private var invalidFiles = 0
    private var missingCurrent = 0
    private var rejected = 0

    fun match(item: ItemStack?): String? {
        val signature = Signature.fromItem(item) ?: return null
        var id = ids[signature]
        if (id == null) id = plainIds[signature.plain()]
        if (id == null && signature.material == Material.PLAYER_HEAD && signature.skinUrl == null &&
            (signature.name != null || signature.lore.isNotEmpty())
        ) {
            id = skinlessHeadIds[signature.plain().withoutSkin()]
        }
        if (id == null) id = currentIds[signature.plain()]
        if (id == null) return null
        val traits = traitsById[id]
        if (traits != null && !traits.allows(item)) {
            rejected++
            return null
        }
        return id
    }

    fun modelFor(id: String): Int? = traitsById[id]?.legacyModel

    fun headSkinFor(id: String): String? = headSkinsById[id]

    /** The `Display` of the 1.15.2 definition (colour codes applied), or null if it had none. */
    fun legacyNameFor(id: String): String? = legacyNamesById[id]

    /** The `Lore` of the 1.15.2 definition (colour codes applied), or null if it had none. */
    fun legacyLoreFor(id: String): List<String>? = legacyLoresById[id]

    fun summary(): String =
        "読込ファイル ${files}件（構文エラー ${invalidFiles}件）、一意な旧定義 ${ids.size}件、" +
            "現行定義 ${currentIds.size}件（重複 ${currentAmbiguous.size}件）、" +
            "同じ見た目の重複 ${ambiguous.size}件、現行MMに無いID ${missingCurrent}件、" +
            "特徴の不一致で除外 ${rejected}件"

    private fun add(id: String, config: Map<*, *>, currentItem: (String) -> ItemStack?) {
        if (config["Id"] == null) return
        val current = currentItem(id)
        if (current == null || current.type.isAir) {
            missingCurrent++
            return
        }
        traitsById[id] = Traits.fromDefinition(config, current)
        val legacyName = Signature.colored(config["Display"])
        if (legacyName != null) legacyNamesById[id] = legacyName
        val legacyLore = (config["Lore"] as? List<*>)?.mapNotNull { Signature.colored(it) }
        if (!legacyLore.isNullOrEmpty()) legacyLoresById[id] = legacyLore
        val signature = Signature.fromDefinition(config, current.type) ?: return
        if (signature.skinUrl != null) headSkinsById[id] = signature.skinUrl

        val previous = ids[signature]
        if (previous == null) ids[signature] = id else if (previous != id) ambiguous.add(signature)

        val plain = signature.plain()
        val oldPlain = plainIds[plain]
        if (oldPlain == null) plainIds[plain] = id else if (oldPlain != id) plainAmbiguous.add(plain)

        if (signature.skinUrl != null && (signature.name != null || signature.lore.isNotEmpty())) {
            val withoutSkin = plain.withoutSkin()
            val previousHead = skinlessHeadIds[withoutSkin]
            if (previousHead == null) skinlessHeadIds[withoutSkin] = id
            else if (previousHead != id) skinlessHeadAmbiguous.add(withoutSkin)
        }
    }

    /** Indexes the item the current MythicMobs generates for [id], so items that only match it are found too. */
    private fun addCurrent(id: String, currentItem: (String) -> ItemStack?) {
        val first = currentItem(id) ?: return
        if (first.type.isAir) return
        val signature = Signature.fromItem(first)?.plain() ?: return
        // A definition with random name/lore has no stable look to match against.
        val second = currentItem(id) ?: return
        if (Signature.fromItem(second)?.plain() != signature) return
        if (id !in traitsById) traitsById[id] = Traits.fromCurrent(first)
        val previous = currentIds[signature]
        if (previous == null) currentIds[signature] = id
        else if (!previous.equals(id, ignoreCase = true)) currentAmbiguous.add(signature)
    }

    /** What a definition fixes on every item it generates, used to rule out look-alikes. */
    private class Traits(
        /** Model of the old definition (restored onto items that lost it). */
        val legacyModel: Int?,
        private val unbreakable: Boolean,
        private val color: Color?,
    ) {
        /** Every model an item of this ID may legitimately carry (old and current definition). */
        val models = HashSet<Int>()

        fun allows(item: ItemStack?): Boolean {
            val meta = item?.itemMeta ?: return true
            if (meta.hasCustomModelData() && meta.customModelData !in models) return false
            if (unbreakable && !meta.isUnbreakable) return false
            return color == null || meta !is LeatherArmorMeta || color == meta.color
        }

        companion object {
            fun fromCurrent(current: ItemStack): Traits {
                val meta = current.itemMeta
                val traits = Traits(
                    null,
                    meta?.isUnbreakable == true,
                    (meta as? LeatherArmorMeta)?.color,
                )
                if (meta != null && meta.hasCustomModelData()) traits.models.add(meta.customModelData)
                return traits
            }

            fun fromDefinition(config: Map<*, *>, current: ItemStack): Traits {
                val rawModel = if ("Model" in config) config["Model"] else config["CustomModelData"]
                val model = integer(rawModel)
                val options = config["Options"] as? Map<*, *> ?: Collections.emptyMap<Any?, Any?>()
                val traits = Traits(
                    model,
                    options["Unbreakable"]?.toString()?.toBoolean() == true,
                    color(options["Color"]),
                )
                if (model != null) traits.models.add(model)
                val meta = current.itemMeta
                if (meta != null && meta.hasCustomModelData()) traits.models.add(meta.customModelData)
                return traits
            }

            private fun color(raw: Any?): Color? {
                if (raw == null) return null
                val parts = raw.toString().split(",")
                if (parts.size != 3) return null
                return try {
                    Color.fromRGB(parts[0].trim().toInt(), parts[1].trim().toInt(), parts[2].trim().toInt())
                } catch (_: IllegalArgumentException) {
                    null
                }
            }
        }
    }

    private data class Signature(
        val material: Material,
        val name: String?,
        val lore: List<String?>,
        val skinUrl: String?,
    ) {
        fun plain(): Signature =
            Signature(material, strip(name), lore.map(::strip), skinUrl)

        fun withoutSkin(): Signature = Signature(material, name, lore, null)

        companion object {
            fun fromDefinition(config: Map<*, *>, material: Material): Signature? {
                val name = colored(config["Display"])
                val lore = coloredList(config["Lore"])
                if (name == null && lore.isEmpty() && material != Material.PLAYER_HEAD) return null
                var skinUrl: String? = null
                if (material == Material.PLAYER_HEAD) {
                    val options = config["Options"] as? Map<*, *> ?: return null
                    skinUrl = decodeSkinUrl(options["SkinTexture"]) ?: return null
                }
                return Signature(material, name, lore, skinUrl)
            }

            fun fromItem(item: ItemStack?): Signature? {
                if (item == null || !item.hasItemMeta()) return null
                val meta = item.itemMeta ?: return null
                val name = meta.displayName()?.let(::readableText)
                var lore: MutableList<String?>? = null
                if (meta.hasLore()) {
                    lore = ArrayList()
                    for (line in meta.lore().orEmpty()) lore.add(readableText(line))
                }
                if (name == null && lore.isNullOrEmpty() && item.type != Material.PLAYER_HEAD) return null
                var skinUrl: String? = null
                if (item.type == Material.PLAYER_HEAD) {
                    val skull = meta as? SkullMeta ?: return null
                    skinUrl = itemSkinUrl(skull)
                    if (skinUrl == null && name == null && lore.isNullOrEmpty()) return null
                }
                return Signature(item.type, name, lore ?: emptyList(), skinUrl)
            }

            fun colored(value: Any?): String? =
                value?.let { ChatColor.translateAlternateColorCodes('&', it.toString()) }

            private fun coloredList(value: Any?): List<String?> {
                if (value !is List<*>) return emptyList()
                return value.map { colored(it) }
            }

            private fun decodeSkinUrl(encoded: Any?): String? {
                if (encoded == null) return null
                return try {
                    val json = String(Base64.getDecoder().decode(encoded.toString()), StandardCharsets.UTF_8)
                    SKIN_URL.find(json)?.groupValues?.get(1)
                } catch (_: IllegalArgumentException) {
                    null
                }
            }

            fun itemSkinUrl(meta: SkullMeta): String? {
                val profile = meta.ownerProfile ?: return null
                return profile.textures.skin?.toString()
            }

            private fun strip(value: String?): String? = value?.let { ChatColor.stripColor(it)?.trim() }
        }
    }

    companion object {
        private val SKIN_URL = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")

        /**
         * @param currentItem the item the current MythicMobs generates for an ID, or null if it has none
         */
        @Throws(IOException::class)
        @JvmStatic
        fun load(
            directory: File,
            currentItem: (String) -> ItemStack?,
            currentIds: Collection<String> = emptyList(),
        ): CurrentLegacyDefinitionMatcher {
            val result = CurrentLegacyDefinitionMatcher()
            if (directory.isDirectory) Files.walk(directory.toPath()).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .filter { it.toString().endsWith(".yml") || it.toString().endsWith(".yaml") }
                    .forEach { path ->
                        result.files++
                        val document = try {
                            Yaml().load<Any?>(String(Files.readAllBytes(path), StandardCharsets.UTF_8))
                        } catch (_: RuntimeException) {
                            result.invalidFiles++
                            return@forEach
                        }
                        if (document !is Map<*, *>) return@forEach
                        for ((key, value) in document) {
                            if (key is String && value is Map<*, *>) result.add(key, value, currentItem)
                        }
                    }
            }
            for (signature in result.ambiguous) result.ids.remove(signature)
            for (signature in result.plainAmbiguous) result.plainIds.remove(signature)
            for (signature in result.skinlessHeadAmbiguous) result.skinlessHeadIds.remove(signature)
            for (id in currentIds) {
                try {
                    result.addCurrent(id, currentItem)
                } catch (_: RuntimeException) {
                }
            }
            // A look shared by an old and a current definition of different IDs is not unique either.
            for ((signature, id) in result.plainIds) {
                val current = result.currentIds[signature]
                if (current != null && !current.equals(id, ignoreCase = true)) result.currentAmbiguous.add(signature)
            }
            for (signature in result.currentAmbiguous) result.currentIds.remove(signature)
            if (result.ids.isEmpty() && result.currentIds.isEmpty()) {
                throw IOException("No usable MM definitions (legacy: $directory, current: ${currentIds.size} IDs)")
            }
            return result
        }

        fun isSkinlessHead(item: ItemStack?): Boolean =
            item != null && item.type == Material.PLAYER_HEAD &&
                item.itemMeta is SkullMeta && Signature.itemSkinUrl(item.itemMeta as SkullMeta) == null

        @JvmStatic
        fun readableText(value: String?): String? {
            if (value == null) return null
            val trimmed = value.trim()
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return value
            return try {
                BaseComponent.toLegacyText(*ComponentSerializer.parse(value))
            } catch (_: RuntimeException) {
                value
            }
        }

        fun readableText(component: Component): String =
            LegacyComponentSerializer.legacySection().serialize(component)

        @JvmStatic
        fun integer(raw: Any?): Int? = when (raw) {
            is Number -> raw.toInt()
            null -> null
            else -> raw.toString().trim().toIntOrNull()
        }
    }
}
