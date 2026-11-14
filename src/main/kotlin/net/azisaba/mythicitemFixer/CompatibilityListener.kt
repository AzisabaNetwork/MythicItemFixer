package net.azisaba.mythicitemFixer

import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.block.BlockState
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.inventory.InventoryHolder
import org.bukkit.loot.Lootable

class CompatibilityListener(
    private val plugin: MythicItemFixer,
    private val converter: ItemConverter,
) : Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    fun onPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (plugin.isOfflineMigrationRunning()) {
            event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "Item data migration is running. Please reconnect in a moment.",
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        plugin.schedulePlayerInventoryRepair(event.player, true)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        plugin.schedulePlayerInventoryRepair(event.player, false)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onOpen(event: InventoryOpenEvent) {
        converter.convertInventory(event.inventory)
        converter.convertInventory(event.player.inventory)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onClose(event: InventoryCloseEvent) {
        converter.convertInventory(event.inventory)
        converter.convertInventory(event.player.inventory)
        (event.player as? Player)?.let(plugin::scheduleRepairAfterClose)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPickup(event: EntityPickupItemEvent) {
        val stack = event.item.itemStack
        if (converter.convert(stack) > 0) event.item.itemStack = stack
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDrop(event: PlayerDropItemEvent) {
        val stack = event.itemDrop.itemStack
        if (converter.convert(stack) > 0) event.itemDrop.itemStack = stack
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSpawn(event: ItemSpawnEvent) {
        val stack = event.entity.itemStack
        if (converter.convert(stack) > 0) event.entity.itemStack = stack
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (plugin.config.getBoolean("scan-newly-loaded-chunks", true) && !plugin.isFullWorldScanRunning()) {
            val chunk = event.chunk
            if (pendingKeys.add(chunkKey(chunk))) pendingChunks.add(chunk)
            if (drainTask == null) {
                drainTask = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { drainPending() }, 1L, 1L)
            }
        }
    }

    // Chunks loaded during a tick are scanned over the following ticks within a time budget,
    // instead of all at once (teleports and flying load dozens of chunks in one tick).
    private val pendingChunks = ArrayDeque<Chunk>()
    private val pendingKeys = HashSet<String>()
    private var drainTask: org.bukkit.scheduler.BukkitTask? = null

    private fun chunkKey(chunk: Chunk) = "${chunk.world.name}:${chunk.x}:${chunk.z}"

    private fun drainPending() {
        val deadline = System.nanoTime() + CHUNK_SCAN_BUDGET_NANOS
        while (pendingChunks.isNotEmpty() && System.nanoTime() < deadline) {
            val chunk = pendingChunks.removeFirst()
            pendingKeys.remove(chunkKey(chunk))
            if (chunk.isLoaded) scanChunk(chunk)
        }
        if (pendingChunks.isEmpty()) {
            drainTask?.cancel()
            drainTask = null
        }
    }

    /** Entities load separately from (and usually after) their chunk, so they are handled here. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        if (!plugin.config.getBoolean("scan-newly-loaded-chunks", true) && !plugin.isFullWorldScanRunning()) return
        for (entity in event.entities) scanEntity(entity)
    }

    fun scanChunk(chunk: Chunk): Int {
        var changes = 0
        // No snapshots: copying every block entity's state was a large part of the scan cost.
        for (state in chunk.getTileEntities(false)) {
            if (state !is InventoryHolder) continue
            // Unopened loot chests hold no items yet; touching them would roll the loot table.
            if (state is Lootable && state.hasLootTable()) continue
            changes += converter.convertInventory(state.inventory)
        }
        for (entity in chunk.entities) changes += scanEntity(entity)
        return changes
    }

    private fun scanEntity(entity: Entity): Int {
        var changes = 0
        when (entity) {
            is Item -> {
                val stack = entity.itemStack
                val itemChanges = converter.convert(stack)
                if (itemChanges > 0) entity.setItemStack(stack)
                changes += itemChanges
            }
            is ItemFrame -> {
                val stack = entity.item
                val itemChanges = converter.convert(stack)
                if (itemChanges > 0) entity.setItem(stack)
                changes += itemChanges
            }
        }
        if (entity is InventoryHolder) changes += converter.convertInventory(entity.inventory)
        if (entity is LivingEntity) {
            val equipment = entity.equipment
            if (equipment != null) {
                val armor = equipment.armorContents
                var armorChanges = 0
                for (stack in armor) armorChanges += converter.convert(stack)
                if (armorChanges > 0) equipment.setArmorContents(armor)
                val main = equipment.itemInMainHand
                val mainChanges = converter.convert(main)
                if (mainChanges > 0) equipment.setItemInMainHand(main)
                val off = equipment.itemInOffHand
                val offChanges = converter.convert(off)
                if (offChanges > 0) equipment.setItemInOffHand(off)
                changes += armorChanges + mainChanges + offChanges
            }
        }
        return changes
    }

    companion object {
        private const val CHUNK_SCAN_BUDGET_NANOS = 2_000_000L
    }
}
