package net.azisaba.mythicitemFixer

import io.lumine.mythic.bukkit.MythicBukkit
import io.lumine.mythic.core.items.ItemExecutor
import org.bukkit.inventory.ItemStack

/** MythicMobs 5 item lookups, compiled against the Mythic-Dist API. */
class MythicItemBridge private constructor(private val itemManager: ItemExecutor) {
    /**
     * The item's Mythic ID. MythicMobs 5 itself moves a leftover 4.x `MYTHIC_TYPE` NBT tag into
     * `mythicmobs:type` (and the Prevent* flags) while answering, so this also migrates the item.
     */
    fun read(item: ItemStack): LegacyItemData? {
        val value = itemManager.getMythicTypeFromItem(item)
        if (value.isNullOrBlank()) return null
        return LegacyItemData(value)
    }

    /** A freshly generated item of the current definition, or null when MythicMobs has no such ID. */
    fun currentItem(id: String): ItemStack? =
        try {
            itemManager.getItemStack(id)
        } catch (_: RuntimeException) {
            null
        }

    companion object {
        @JvmStatic
        fun detect(): MythicItemBridge = MythicItemBridge(MythicBukkit.inst().itemManager)
    }
}
