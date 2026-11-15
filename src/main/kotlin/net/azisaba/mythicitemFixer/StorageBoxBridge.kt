package net.azisaba.mythicitemFixer

import org.bukkit.inventory.ItemStack
import xyz.acrylicstyle.storageBox.utils.StorageBox

/**
 * Repairs the content of a Storage Box (`xyz.acrylicstyle.storageBox`). A box keeps its item outside the
 * inventory, in its own persistent data, so the normal inventory scan never sees it. The box is rewritten
 * through StorageBox's own API, which keeps its type, amount, settings and ID.
 */
internal class StorageBoxBridge(private val plugin: MythicItemFixer) {
    private var errors = 0

    /** Cheap check before calling into StorageBox for every scanned item. */
    fun looksLikeBox(item: ItemStack): Boolean {
        if (item.type.isAir) return false
        try {
            if (item.persistentDataContainer.keys.any { it.namespace == "storagebox" }) return true
        } catch (_: RuntimeException) {
            return false
        }
        if (!item.hasItemMeta()) return false
        val meta = item.itemMeta ?: return false
        if (!meta.hasDisplayName()) return false
        val name = meta.displayName()?.let(CurrentLegacyDefinitionMatcher::readableText) ?: return false
        return name.contains("Storage Box")
    }

    /**
     * The box with its content repaired, or null when the item is not a Storage Box or its content needs no
     * repair. Main thread only.
     */
    fun repair(item: ItemStack): ItemStack? {
        if (!looksLikeBox(item)) return null
        return try {
            val box = StorageBox.getStorageBox(item) ?: return null
            val content = box.componentItemStack ?: return null
            if (content.type.isAir) return null
            val repaired = plugin.repairItem(content) ?: return null
            val amount = box.amount
            box.importComponent(repaired)
            box.amount = amount // importComponent takes the content's amount (1); the box's count is unchanged
            val repairedBox = box.itemStack
            repairedBox.amount = item.amount
            if (repairedBox.isSimilar(item)) null else repairedBox
        } catch (error: RuntimeException) {
            logError(error)
            null
        } catch (error: LinkageError) {
            logError(error)
            null
        }
    }

    private fun logError(error: Throwable) {
        if (errors++ < 10) {
            plugin.logger.warning("Could not repair the content of a Storage Box: $error")
        }
    }
}
