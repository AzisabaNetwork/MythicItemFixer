package net.azisaba.mythicitemFixer

import io.papermc.paper.datacomponent.DataComponentTypes
import org.bukkit.NamespacedKey
import org.bukkit.block.Container
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
import org.bukkit.persistence.PersistentDataType

/**
 * Gives items that still carry the MythicMobs 4 `MYTHIC_TYPE` tag their `mythicmobs:type`, including
 * items inside shulker boxes and bundles.
 */
class ItemConverter(
    val bridge: MythicItemBridge,
    private val convertedLogger: (String) -> Unit,
    private val errorLogger: (Throwable) -> Unit,
    private val exclusions: () -> MmidExclusions,
) {
    val normalizer = StackNormalizer(bridge::currentItem)
    private var legacyNames: ((String) -> String?)? = null
    private var checked = 0L
    private var normalized = 0L
    private var converted = 0L
    private var excluded = 0L
    private var errors = 0L

    /** The 1.15.2 definition name per ID, used to recognise MM names that were renamed since. */
    fun setLegacyNames(legacyNames: ((String) -> String?)?) {
        this.legacyNames = legacyNames
    }

    fun convert(item: ItemStack?): Int = convert(item, 0)

    private fun convert(item: ItemStack?, depth: Int): Int {
        if (item == null || item.type.isAir || depth > 8) return 0
        checked++
        var changes = 0
        try {
            // Read through the PDC view: item.itemMeta clones the whole meta on every call.
            var id = item.persistentDataContainer.get(TYPE, PersistentDataType.STRING)?.trim()
            if (id.isNullOrEmpty()) {
                // MythicMobs writes the ID onto the item it reads, so read a copy and keep the
                // result only when the ID is not excluded
                val copy = item.clone()
                val legacy = bridge.read(copy)
                if (legacy != null) {
                    if (exclusions().isExcluded(legacy.mythicType)) {
                        excluded++
                    } else {
                        copy.itemMeta?.let { item.itemMeta = it }
                        converted++
                        changes++
                        convertedLogger(legacy.mythicType)
                        id = legacy.mythicType.trim()
                    }
                }
            }
            if (!id.isNullOrEmpty() && !exclusions().isExcluded(id)) {
                changes += normalize(item, id)
            }
            changes += convertNested(item, depth)
        } catch (exception: Exception) {
            errors++
            if (errors <= 10) errorLogger(exception)
        }
        return changes
    }

    /** Makes an item with an ID stack with `/mm i get` items (see [StackNormalizer]). */
    private fun normalize(item: ItemStack, id: String): Int {
        val result = normalizer.normalize(item, id, legacyNames?.invoke(id)) ?: return 0
        result.itemMeta?.let { item.itemMeta = it }
        normalized++
        return 1
    }

    /** Converts items inside shulker boxes and bundles with one ItemMeta lookup. */
    @Suppress("UnstableApiUsage")
    private fun convertNested(item: ItemStack, depth: Int): Int {
        // Cheap component check first so ordinary items never build an ItemMeta.
        if (!item.hasData(DataComponentTypes.CONTAINER) && !item.hasData(DataComponentTypes.BUNDLE_CONTENTS)) return 0
        val meta = item.itemMeta ?: return 0
        var changes = 0
        if (meta is BlockStateMeta) {
            if (meta.hasBlockState()) {
                val state = meta.blockState
                if (state is Container) {
                    val nestedChanges = convertInventory(state.inventory, depth + 1)
                    if (nestedChanges > 0) {
                        meta.blockState = state
                        item.itemMeta = meta
                    }
                    changes += nestedChanges
                }
            }
        } else if (meta is BundleMeta && meta.hasItems()) {
            val items = ArrayList(meta.items)
            for (nested in items) changes += convert(nested, depth + 1)
            if (changes > 0) {
                meta.setItems(items)
                item.itemMeta = meta
            }
        }
        return changes
    }

    fun convertInventory(inventory: Inventory): Int = convertInventory(inventory, 0)

    private fun convertInventory(inventory: Inventory, depth: Int): Int {
        var changes = 0
        // getContents() reads the item list directly; getItem(slot) on loot containers rolls the
        // loot table, which is what made chunk scans expensive.
        val contents = inventory.contents
        for (slot in contents.indices) {
            val item = contents[slot] ?: continue
            if (item.type.isAir) continue
            val itemChanges = convert(item, depth)
            if (itemChanges > 0 && item != null) inventory.setItem(slot, item)
            changes += itemChanges
        }
        return changes
    }

    fun getChecked(): Long = checked
    fun getConverted(): Long = converted
    fun getExcluded(): Long = excluded
    fun getErrors(): Long = errors
    fun getNormalized(): Long = normalized

    companion object {
        @JvmField
        val TYPE = NamespacedKey("mythicmobs", "type")
    }
}
