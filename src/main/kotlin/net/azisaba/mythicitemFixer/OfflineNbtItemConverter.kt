package net.azisaba.mythicitemFixer

import java.io.IOException
import java.util.LinkedHashMap

/**
 * Adds `mythicmobs:type` next to a leftover MythicMobs 4 `MYTHIC_TYPE` anywhere in raw player NBT.
 * Since 1.20.5 both sit in an item's `minecraft:custom_data` component, which also holds Bukkit's
 * `PublicBukkitValues`.
 */
object OfflineNbtItemConverter {
    @Throws(IOException::class)
    @JvmStatic
    fun convert(root: NbtIo.Tag.CompoundTag, exclusions: MmidExclusions = MmidExclusions.NONE): Int =
        convertRecursively(root, 0, exclusions)

    @Throws(IOException::class)
    private fun convertRecursively(tag: NbtIo.Tag, depth: Int, exclusions: MmidExclusions): Int {
        if (depth > 128) throw IOException("NBT nesting is too deep")
        var changed = 0
        when (tag) {
            is NbtIo.Tag.CompoundTag -> {
                val compound = tag.value
                val mythicType = compound["MYTHIC_TYPE"]
                if (mythicType is NbtIo.Tag.StringTag &&
                    mythicType.value.trim().isNotEmpty() &&
                    !exclusions.isExcluded(mythicType.value)
                ) {
                    val publicValues = compound.getOrPut("PublicBukkitValues") {
                        NbtIo.Tag.CompoundTag(LinkedHashMap())
                    }
                    if (publicValues is NbtIo.Tag.CompoundTag && "mythicmobs:type" !in publicValues.value) {
                        val values = publicValues.value
                        values["mythicmobs:type"] = NbtIo.Tag.StringTag(mythicType.value)
                        copyFlag(compound, values, "PreventAnvil", "mythicmobs:prevent_repairing_with")
                        copyFlag(compound, values, "PreventCrafting", "mythicmobs:prevent_crafting_with")
                        copyFlag(compound, values, "PreventEnchanting", "mythicmobs:prevent_enchanting")
                        copyFlag(compound, values, "PreventSmithing", "mythicmobs:prevent_smithing_with")
                        changed++
                    }
                }
                for (child in compound.values) changed += convertRecursively(child, depth + 1, exclusions)
            }
            is NbtIo.Tag.ListTag -> {
                for (child in tag.elements) changed += convertRecursively(child, depth + 1, exclusions)
            }
            else -> Unit
        }
        return changed
    }

    private fun copyFlag(
        source: Map<String, NbtIo.Tag>,
        target: MutableMap<String, NbtIo.Tag>,
        oldName: String,
        newName: String,
    ) {
        if (oldName in source && newName !in target) {
            target[newName] = NbtIo.Tag.ByteTag(1)
        }
    }
}
