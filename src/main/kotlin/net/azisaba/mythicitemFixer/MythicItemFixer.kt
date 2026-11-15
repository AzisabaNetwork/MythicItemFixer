package net.azisaba.mythicitemFixer

import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.profile.PlayerProfile
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.logging.Level

/**
 * Keeps MythicMobs 4 items working on 1.21.11 / MythicMobs 5: moves a leftover `MYTHIC_TYPE`
 * tag to `mythicmobs:type`, and identifies items that lost their ID from the old Items YAML.
 */
class MythicItemFixer : JavaPlugin() {
    private var converter: ItemConverter? = null
    private var listener: CompatibilityListener? = null
    private var offlineMigrator: OfflinePlayerDataMigrator? = null
    private var fullWorldScanner: FullWorldScanner? = null
    private var legacyMatcher: CurrentLegacyDefinitionMatcher? = null
    private var legacyMatcherError: String? = null
    private var storageBoxBridge: StorageBoxBridge? = null

    @Volatile
    var exclusions: MmidExclusions = MmidExclusions.NONE
        private set
    private val chunkQueue = ArrayDeque<Chunk>()
    private var chunkTask: BukkitTask? = null

    override fun onEnable() {
        saveDefaultConfig()
        reloadExclusions()
        if (server.pluginManager.getPlugin("MythicMobs") == null) {
            logger.severe("MythicMobs is not installed; disabling.")
            server.pluginManager.disablePlugin(this)
            return
        }
        val bridge = MythicItemBridge.detect()
        val boxBridge = if (server.pluginManager.getPlugin("StorageBox") != null) {
            try {
                StorageBoxBridge(this).also { storageBoxBridge = it }
            } catch (_: LinkageError) {
                logger.warning("StorageBox was found but its API could not be loaded; box contents are not repaired.")
                null
            }
        } else {
            null
        }
        val itemConverter = ItemConverter(
            bridge,
            { id -> if (config.getBoolean("log-converted-items", false)) logger.info("Preserved Mythic item ID: $id") },
            { error -> logger.log(Level.WARNING, "Could not inspect an item for a legacy Mythic ID", error) },
            { exclusions },
            boxBridge?.let { api -> { item: ItemStack -> api.repair(item) } },
        )
        converter = itemConverter
        val compatListener = CompatibilityListener(this, itemConverter)
        listener = compatListener
        offlineMigrator = OfflinePlayerDataMigrator(this)
        fullWorldScanner = FullWorldScanner(this, compatListener)
        server.pluginManager.registerEvents(compatListener, this)
        reloadLegacyMatcher()

        if (config.getBoolean("scan-online-players-on-enable", true)) {
            for (player in Bukkit.getOnlinePlayers()) scanPlayer(player)
        }
        if (config.getBoolean("scan-loaded-chunks-on-enable", true)) queueLoadedChunks()
        if (config.getBoolean("scan-offline-playerdata-on-enable", true)) {
            startOfflineScan(server.consoleSender)
        }
        if (config.getBoolean("scan-all-world-chunks-on-enable", false)) {
            fullWorldScanner?.start { message -> logger.info(message) }
        }

        val interval = config.getLong("online-player-scan-interval-ticks", 200L)
        if (interval > 0) {
            Bukkit.getScheduler().runTaskTimer(
                this,
                Runnable { Bukkit.getOnlinePlayers().forEach(::scanPlayer) },
                interval,
                interval,
            )
        }
        logger.info("Enabled. Existing item data is preserved.")
    }

    fun scanPlayer(player: Player) {
        val itemConverter = converter ?: return
        itemConverter.convertInventory(player.inventory)
        itemConverter.convertInventory(player.enderChest)
        val cursor = player.itemOnCursor
        if (itemConverter.convert(cursor) > 0) player.setItemOnCursor(cursor)
    }

    /** After a chest or other container closes: items just taken out are repaired like at join. */
    fun scheduleRepairAfterClose(player: Player) {
        if (!config.getBoolean("repair-inventory-on-close", true)) return
        Bukkit.getScheduler().runTask(this, Runnable {
            if (player.isOnline) repairInventory(player, false, true)
        })
    }

    internal fun schedulePlayerInventoryRepair(player: Player, joining: Boolean) {
        if (joining && !config.getBoolean("repair-inventory-on-join", true)) return
        if (!joining && !config.getBoolean("repair-inventory-on-world-change", true)) return
        schedulePlayerInventoryRepair(player, 1L)
        val delay = maxOf(0L, config.getLong("repair-inventory-on-join-delay-ticks", 20L))
        if (delay > 1L) schedulePlayerInventoryRepair(player, delay)
    }

    private fun schedulePlayerInventoryRepair(player: Player, delay: Long) {
        Bukkit.getScheduler().runTaskLater(this, Runnable {
            if (!player.isOnline) return@Runnable
            scanPlayer(player)
            repairInventory(player, false, true)
        }, delay)
    }

    /**
     * Converts one item in place. This is safe to call repeatedly and is exposed
     * for plugins that keep ItemStacks outside Bukkit inventories.
     */
    fun preserveMythicId(item: ItemStack?): Int = converter?.convert(item) ?: 0

    /**
     * Same as [preserveMythicId] for every non-null entry. Main thread only.
     * Used by AziSync after it applies inventories loaded from its database.
     *
     * @return how many items received an ID
     */
    fun preserveMythicIds(items: Array<ItemStack?>?): Int {
        val itemConverter = converter ?: return 0
        if (items == null) return 0
        var changes = 0
        for (item in items) changes += itemConverter.convert(item)
        return changes
    }

    /**
     * Whether `excluded-mmids` covers this MMID. Thread-safe, so plugins that convert raw NBT
     * off the main thread (AziSync's MPDB migration) can honour the same exclusions.
     */
    fun isMythicIdExcluded(mmid: String?): Boolean = exclusions.isExcluded(mmid)

    /**
     * Runs the automatic join repair (old tag → ID, then the legacy-definition match) on a player's
     * inventory right now. For plugins that replace the inventory after the join-time repair already
     * ran, such as AziSync loading it from MySQL. Main thread only; honours
     * `repair-inventory-on-join`.
     */
    fun repairPlayerInventory(player: Player?) {
        val itemConverter = converter ?: return
        if (player == null || !player.isOnline) return
        scanPlayer(player)
        if (config.getBoolean("repair-inventory-on-join", true)) repairInventory(player, false, true)
    }

    /**
     * The MMID of one item, without changing it: its `mythicmobs:type`, else its MythicMobs 4
     * `MYTHIC_TYPE`, else the single 1.15.2 definition it matches (material, name, lore, skin; same rules as
     * the inventory repair). Null for ordinary items, ambiguous matches and `excluded-mmids`.
     * For plugins that keep items outside inventories, such as StorageBox's contents. Main thread only.
     */
    fun resolveMythicId(item: ItemStack?): String? {
        val itemConverter = converter ?: return null
        if (item == null || item.type.isAir) return null
        val copy = item.clone()
        itemConverter.convert(copy)
        val stored = copy.itemMeta?.persistentDataContainer?.get(ItemConverter.TYPE, PersistentDataType.STRING)
        var id = stored
        if (id == null || id.trim().isEmpty()) id = legacyMatcher?.match(copy)
        val trimmed = id?.trim()
        if (trimmed.isNullOrEmpty() || exclusions.isExcluded(trimmed)) return null
        return trimmed
    }

    /**
     * Repairs one item exactly like `/mmidfix inventory` does (old tag → ID, then the 1.15.2 definition match,
     * Model, skin, attributes, name and the stack fix). Returns a repaired copy, or null when nothing changes.
     * The item itself is not modified. For plugins that keep items outside inventories, such as StorageBox's
     * contents. Main thread only.
     */
    fun repairItem(item: ItemStack?): ItemStack? {
        val itemConverter = converter ?: return null
        val matcher = legacyMatcher ?: return null
        if (item == null || item.type.isAir || !item.hasItemMeta()) return null
        val outcome = repairOne(item, matcher, itemConverter)
        return (outcome as? RepairOutcome.Done)?.result?.takeIf { it.changed }?.item
    }

    /** A freshly generated item of the current MythicMobs definition (amount 1), or null if there is none. Main thread only. */
    fun currentMythicItem(id: String?): ItemStack? {
        val itemConverter = converter ?: return null
        if (id == null) return null
        val current = itemConverter.bridge.currentItem(id) ?: return null
        if (current.type.isAir) return null
        return current.clone().apply { amount = 1 }
    }

    private fun queueLoadedChunks(): Int {
        var queued = 0
        for (world in Bukkit.getWorlds()) {
            for (chunk in world.loadedChunks) {
                chunkQueue.offer(chunk)
                queued++
            }
        }
        if (chunkTask == null) {
            chunkTask = Bukkit.getScheduler().runTaskTimer(this, Runnable {
                val limit = maxOf(1, config.getInt("chunks-per-tick", 2))
                var processed = 0
                while (processed < limit) {
                    val chunk = chunkQueue.poll()
                    if (chunk == null) {
                        chunkTask?.cancel()
                        chunkTask = null
                        logger.info("Loaded-chunk scan completed.")
                        return@Runnable
                    }
                    if (chunk.isLoaded) listener?.scanChunk(chunk)
                    processed++
                }
            }, 1L, 1L)
        }
        return queued
    }

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): Boolean {
        val admin = sender.hasPermission(PERM_ADMIN)
        val sub = if (args.isEmpty()) {
            if (admin) "status" else "help"
        } else {
            args[0].lowercase(Locale.ROOT)
        }
        if (if (sub in ADMIN_SUBCOMMANDS) !admin else !sender.hasPermission(PERM_USE)) {
            send(sender, "§cこのコマンドを実行する権限がありません。")
            return true
        }
        when (sub) {
            "status" -> {
                val itemConverter = converter ?: return true
                send(sender, "§f動作状況")
                sender.sendMessage(
                    "§7・旧タグの変換：確認 §f${itemConverter.getChecked()}§7個 / ID付与 §a" +
                        "${itemConverter.getConverted()}§7個 / 除外 §f${itemConverter.getExcluded()}§7個 / エラー " +
                        errorCount(itemConverter.getErrors()) + " §7/ スタック用に整形 §a" +
                        "${itemConverter.getNormalized()}§7個",
                )
                sender.sendMessage("§7・除外MMID：§f" + exclusions.summary())
                val matcher = legacyMatcher
                sender.sendMessage(
                    "§7・旧定義：" + if (matcher != null) {
                        "§f" + matcher.summary()
                    } else {
                        "§c読み込めていません（$legacyMatcherError）"
                    },
                )
                if (isOfflineMigrationRunning()) sender.sendMessage("§7・オフラインプレイヤーの変換：§e実行中")
                if (isFullWorldScanRunning()) {
                    sender.sendMessage("§7・" + (fullWorldScanner?.status("ワールド全体の走査：§e実行中§7") ?: ""))
                }
                sender.sendMessage("§7コマンド一覧は §e/mmidfix help")
            }
            "hand" -> {
                val player = sender as? Player ?: run {
                    send(sender, "§eプレイヤーとして実行してください。")
                    return true
                }
                val itemConverter = converter ?: return true
                val item = player.inventory.itemInMainHand
                if (item.type.isAir) {
                    send(sender, "§e手に何も持っていません。")
                    return true
                }
                val converted = itemConverter.convert(item)
                player.inventory.setItemInMainHand(item)
                val id = if (item.hasItemMeta()) {
                    item.itemMeta?.persistentDataContainer?.get(ItemConverter.TYPE, PersistentDataType.STRING)
                } else {
                    null
                }
                if (id == null) {
                    send(
                        sender,
                        "§f${itemName(item)}§7：MMIDはありません。§7旧定義との照合は §e/mmidfix inventory preview",
                    )
                } else {
                    send(
                        sender,
                        "§f${itemName(item)}§7：MMID §a$id" +
                            if (converted > 0) " §a（旧タグから付与しました）" else " §7（付与済み）",
                    )
                }
            }
            "inventory" -> {
                val player = sender as? Player ?: run {
                    send(sender, "§eプレイヤーとして実行してください。")
                    return true
                }
                repairInventory(player, args.size > 1 && args[1].equals("preview", ignoreCase = true), false)
            }
            "reload" -> {
                reloadConfig()
                reloadExclusions()
                reloadLegacyMatcher()
                converter?.normalizer?.clearCache()
                val matcher = legacyMatcher
                send(
                    sender,
                    if (matcher != null) {
                        "§a設定と旧定義を読み直しました。§7${matcher.summary()}、除外MMID ${exclusions.summary()}"
                    } else {
                        "§c旧定義を読み込めませんでした：$legacyMatcherError"
                    },
                )
            }
            "scan" -> {
                Bukkit.getOnlinePlayers().forEach(::scanPlayer)
                val chunks = queueLoadedChunks()
                send(
                    sender,
                    "§aオンラインプレイヤー ${Bukkit.getOnlinePlayers().size}人を走査しました。§7読込済みチャンク " +
                        "${chunks}件を順に走査します（完了はコンソールに表示）。",
                )
            }
            "offline" -> startOfflineScan(sender)
            "worldscan" -> {
                val started = fullWorldScanner?.start { message ->
                    logger.info(message)
                    if (sender is Player && sender.isOnline) send(sender, "§a$message")
                } ?: false
                send(
                    sender,
                    if (started) {
                        "§aワールド全体の走査を始めました。§7既存のチャンクを順に読み込んで保存します。進み具合は §e/mmidfix status"
                    } else {
                        "§e既に実行中です。§7" + (fullWorldScanner?.status("現在") ?: "")
                    },
                )
            }
            else -> help(sender)
        }
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            val options = ArrayList<String>()
            if (sender.hasPermission(PERM_USE)) options.addAll(PLAYER_SUBCOMMANDS)
            if (sender.hasPermission(PERM_ADMIN)) options.addAll(ADMIN_SUBCOMMANDS)
            return matching(options, args[0])
        }
        if (args.size == 2 && args[0].equals("inventory", ignoreCase = true) &&
            sender.hasPermission(PERM_USE)
        ) {
            return matching(listOf("preview"), args[1])
        }
        return emptyList()
    }

    private fun startOfflineScan(sender: CommandSender) {
        val migrator = offlineMigrator ?: return
        val started = migrator.start { result ->
            logger.info(result.message())
            if (sender is Player && sender.isOnline) send(sender, "§a${result.message()}")
        }
        send(
            sender,
            if (started) {
                "§aオフラインプレイヤーのファイルを走査しています。§7ログイン中のプレイヤーは省きます。"
            } else {
                "§e既に実行中です。"
            },
        )
    }

    private fun repairInventory(player: Player, preview: Boolean, automatic: Boolean) {
        val matcher = legacyMatcher
        if (matcher == null) {
            if (!automatic) {
                send(player, "§c旧MythicMobsのItemsを読み込めませんでした。")
                player.sendMessage(
                    "§7plugins/MythicItemFixer/legacy-mythic-items/ に、1.15.2のItemsの中身を入れて §e/mmidfix reload",
                )
                player.sendMessage("§7詳細：$legacyMatcherError")
            }
            return
        }
        val itemConverter = converter ?: return
        var inspected = 0
        var alreadyTagged = 0
        var noMatch = 0
        var missingCurrent = 0
        var excludedCount = 0
        var idsRepaired = 0
        var modelsRepaired = 0
        var skinsRepaired = 0
        var attributesRepaired = 0
        var namesRepaired = 0
        var restacked = 0
        var skinlessGuesses = 0
        var repairedBoxContents = 0
        var details = 0
        var hidden = 0
        val inventories = arrayOf(player.inventory, player.enderChest)
        if (!automatic) {
            send(
                player,
                if (preview) {
                    "§fインベントリとエンダーチェストの修復候補（§e確認のみ・変更しません§f）"
                } else {
                    "§fインベントリとエンダーチェストを修復します"
                },
            )
        }
        for (pass in inventories.indices) {
            val inventory = inventories[pass]
            val enderChest = pass == 1
            for (slot in 0 until inventory.size) {
                val item = inventory.getItem(slot) ?: continue
                if (item.type.isAir || !item.hasItemMeta()) continue
                inspected++
                // A Storage Box keeps its content outside the inventory. Repair it with the same item
                // repair, then write the box back (the box item itself is not a Mythic item).
                val box = storageBoxBridge?.repair(item)
                if (box != null) {
                    if (!preview) inventory.setItem(slot, box)
                    repairedBoxContents++
                    if (!automatic) {
                        if (details < DETAIL_LIMIT) {
                            player.sendMessage(
                                "§a ✔ ${slotLabel(enderChest, slot)} §f${itemName(item)} §7→ §7中身を修復",
                            )
                            details++
                        } else {
                            hidden++
                        }
                    }
                    continue
                }
                when (val outcome = repairOne(item, matcher, itemConverter)) {
                    RepairOutcome.NotMythic -> noMatch++ // ordinary items: counted, not listed
                    is RepairOutcome.Excluded -> excludedCount++ // excluded MMIDs get no ID, Model or skin fixes
                    is RepairOutcome.MissingCurrent -> {
                        missingCurrent++
                        if (!automatic) {
                            if (details < DETAIL_LIMIT) {
                                player.sendMessage(
                                    "§e ✘ ${slotLabel(enderChest, slot)} §f${itemName(item)}§7：ID ${outcome.id} が今のMMにありません",
                                )
                                details++
                            } else {
                                hidden++
                            }
                        }
                    }
                    is RepairOutcome.Done -> {
                        val result = outcome.result
                        if (result.wasTagged) alreadyTagged++
                        if (result.inferredWithoutSkin) skinlessGuesses++
                        if (!result.changed) continue
                        if (!automatic) {
                            if (details < DETAIL_LIMIT) {
                                val fixes = ArrayList<String>()
                                if (result.needsId) fixes.add("MMID §a${result.id}")
                                if (result.model != null) fixes.add("§7Model §a${result.model}")
                                if (result.skinRestored) fixes.add("§7スキン §a復元")
                                if (result.attributesRestored > 0) {
                                    fixes.add("§7属性 §a${result.attributesRestored}件")
                                }
                                if (result.nameRestored) fixes.add("§7名前 §a${plain(result.fixed.displayName()!!)}")
                                if (result.restacked) {
                                    fixes.add("§7スタック §a${stackableParts(result.fixed, result.item)}")
                                }
                                player.sendMessage(
                                    "§a ✔ ${slotLabel(enderChest, slot)} §f${itemName(item)} §7→ " +
                                        fixes.joinToString("§7、") +
                                        if (result.inferredWithoutSkin) " §e（スキン無し・名前とLoreで推定）" else "",
                                )
                                details++
                            } else {
                                hidden++
                            }
                        }
                        if (!preview) inventory.setItem(slot, result.item)
                        if (result.needsId) idsRepaired++
                        if (result.model != null) modelsRepaired++
                        if (result.skinRestored) skinsRepaired++
                        if (result.attributesRestored > 0) attributesRepaired++
                        if (result.nameRestored) namesRepaired++
                        if (result.restacked) restacked++
                    }
                }
            }
        }
        val repaired = idsRepaired + modelsRepaired + skinsRepaired + attributesRepaired + namesRepaired +
            restacked + repairedBoxContents
        if (automatic) {
            if (repaired > 0) {
                send(
                    player,
                    "§aインベントリを自動修復しました §7（MMID $idsRepaired・Model $modelsRepaired" +
                        "・スキン $skinsRepaired・属性 $attributesRepaired・名前 $namesRepaired" +
                        "・スタック $restacked・Storage Box $repairedBoxContents）",
                )
            }
            return
        }
        if (hidden > 0) player.sendMessage("§7 …ほか ${hidden}件")
        player.sendMessage(
            "§f結果：" + (if (preview) "修復できるもの" else "修復したもの") + " §aMMID $idsRepaired" +
                "§7・§aModel $modelsRepaired§7・§aスキン $skinsRepaired§7・§a属性 $attributesRepaired" +
                "§7・§a名前 $namesRepaired§7・§aスタック $restacked§7・§aStorage Box $repairedBoxContents",
        )
        player.sendMessage(
            "§7確認 ${inspected}個（MMID付与済み $alreadyTagged・MMアイテムではない/一致なし " +
                "$noMatch・今のMMに無いID $missingCurrent・除外 $excludedCount）",
        )
        if (skinlessGuesses > 0) {
            player.sendMessage(
                "§eスキンが失われたヘッド $skinlessGuesses" +
                    "個は名前とLoreだけで推定しています。上の候補を確認してください。",
            )
        }
        if (repaired == 0) {
            player.sendMessage(
                if (missingCurrent == 0) {
                    "§a修復が必要なアイテムはありません。"
                } else {
                    "§e今のMMに無いIDのアイテムは修復できません。MM側の定義を確認してください。"
                },
            )
        } else if (preview) {
            player.sendMessage("§7実行するには §e/mmidfix inventory")
        }
    }

    /**
     * The shared repair step: old tag → ID, then the 1.15.2 definition match, Model, skin, attributes,
     * name and the stack fix. [item] is not modified; the repaired copy is in [RepairOutcome.Done].
     */
    private fun repairOne(
        item: ItemStack,
        matcher: CurrentLegacyDefinitionMatcher,
        itemConverter: ItemConverter,
    ): RepairOutcome {
        val meta = item.itemMeta ?: return RepairOutcome.NotMythic
        var id = meta.persistentDataContainer.get(ItemConverter.TYPE, PersistentDataType.STRING)
        val needsId = id == null || id.trim().isEmpty()
        if (needsId) id = matcher.match(item)
        if (id == null) return RepairOutcome.NotMythic
        if (exclusions.isExcluded(id)) return RepairOutcome.Excluded(id)
        val current = itemConverter.bridge.currentItem(id) ?: return RepairOutcome.MissingCurrent(id)
        val legacyName = matcher.legacyNameFor(id)
        var model: Int? = null
        if (!meta.hasCustomModelData()) {
            val standard = current.itemMeta
            if (standard != null && standard.hasCustomModelData()) model = standard.customModelData
            if (model == null) model = matcher.modelFor(id)
        }
        val missingSkin = CurrentLegacyDefinitionMatcher.isSkinlessHead(item)
        val inferredWithoutSkin = needsId && missingSkin
        val canRestoreSkin = missingSkin && current.type == Material.PLAYER_HEAD &&
            (
                current.itemMeta is SkullMeta &&
                    !CurrentLegacyDefinitionMatcher.isSkinlessHead(current) ||
                    matcher.headSkinFor(id) != null
                )
        val missingAttributes = missingAttributes(meta, current)
        val restoreAttributes = !missingAttributes.isEmpty
        val wantedName = nameToRestore(meta, current, legacyName)
        val fixed = item.clone()
        val fixedMeta = fixed.itemMeta ?: return RepairOutcome.NotMythic
        var skinRestored = false
        if (needsId) fixedMeta.persistentDataContainer.set(ItemConverter.TYPE, PersistentDataType.STRING, id)
        if (model != null) fixedMeta.setCustomModelData(model)
        if (canRestoreSkin) {
            skinRestored = restoreHeadProfile(
                fixedMeta as SkullMeta,
                current,
                matcher.headSkinFor(id),
                id,
            )
        }
        if (restoreAttributes) restoreAttributes(fixedMeta, missingAttributes)
        if (wantedName != null) fixedMeta.displayName(wantedName)
        fixed.itemMeta = fixedMeta
        val stackable = itemConverter.normalizer.normalize(fixed, id, legacyName)
        val restack = stackable != null
        return RepairOutcome.Done(
            RepairResult(
                id = id,
                item = if (restack) stackable!! else fixed,
                fixed = fixed,
                needsId = needsId,
                wasTagged = !needsId,
                model = model,
                skinRestored = skinRestored,
                attributesRestored = missingAttributes.size(),
                nameRestored = wantedName != null,
                restacked = restack,
                inferredWithoutSkin = inferredWithoutSkin,
            ),
        )
    }

    private sealed interface RepairOutcome {
        object NotMythic : RepairOutcome
        data class Excluded(val id: String) : RepairOutcome
        data class MissingCurrent(val id: String) : RepairOutcome
        data class Done(val result: RepairResult) : RepairOutcome
    }

    private class RepairResult(
        val id: String,
        val item: ItemStack,
        val fixed: ItemStack,
        val needsId: Boolean,
        val wasTagged: Boolean,
        val model: Int?,
        val skinRestored: Boolean,
        val attributesRestored: Int,
        val nameRestored: Boolean,
        val restacked: Boolean,
        val inferredWithoutSkin: Boolean,
    ) {
        val changed: Boolean
            get() = needsId || model != null || skinRestored || attributesRestored > 0 || nameRestored || restacked
    }

    private fun reloadExclusions() {
        exclusions = MmidExclusions.parse(config.getStringList("excluded-mmids"))
        logger.info("Excluded MMIDs: ${exclusions.summary()}")
    }

    private fun reloadLegacyMatcher() {
        val itemConverter = converter ?: return
        var definitions = File(dataFolder, "legacy-mythic-items")
        if (!definitions.isDirectory) {
            definitions = File(
                dataFolder.parentFile ?: dataFolder,
                "SearchableInfiniteShop/legacy-mythic-items",
            )
        }
        try {
            val loaded = CurrentLegacyDefinitionMatcher.load(
                definitions,
                itemConverter.bridge::currentItem,
                itemConverter.bridge.allIds(),
            )
            legacyMatcher = loaded
            legacyMatcherError = null
            itemConverter.setLegacyNames(loaded::legacyNameFor)
            logger.info("Legacy item definitions loaded: ${loaded.summary()}")
        } catch (error: IOException) {
            legacyMatcher = null
            legacyMatcherError = error.message
            itemConverter.setLegacyNames(null)
            logger.warning(
                "Automatic inventory repair is unavailable: $legacyMatcherError" +
                    ". Copy the 1.15.2 Items contents to plugins/MythicItemFixer/legacy-mythic-items/.",
            )
        }
    }

    private fun itemName(item: ItemStack): String {
        val meta = item.itemMeta
        return if (meta != null && meta.hasDisplayName()) {
            meta.displayName()?.let(CurrentLegacyDefinitionMatcher::readableText) ?: item.type.name
        } else {
            item.type.name
        }
    }

    /**
     * Attribute modifiers of the current MythicMobs definition for every attribute the item has none
     * for. Attributes the item still carries are left alone (they may have been changed on purpose),
     * so this only fills in modifiers lost during the 1.15 → 1.21 move and is safe to repeat.
     */
    private fun missingAttributes(meta: ItemMeta, current: ItemStack): Multimap<Attribute, AttributeModifier> {
        val missing = LinkedHashMultimap.create<Attribute, AttributeModifier>()
        val standard = current.itemMeta ?: return missing
        if (!standard.hasAttributeModifiers()) return missing
        val wanted = standard.attributeModifiers ?: return missing
        for (attribute in wanted.keySet()) {
            val existing = if (meta.hasAttributeModifiers()) meta.getAttributeModifiers(attribute) else null
            if (existing.isNullOrEmpty()) missing.putAll(attribute, wanted.get(attribute))
        }
        return missing
    }

    /**
     * The current definition's display name when the item's name is clearly the MM name gone wrong:
     * missing, the same text with its colours/formatting lost, or still the 1.15.2 definition's name
     * after the definition was renamed. Any other name (an anvil rename) is kept. Null when nothing to do.
     */
    private fun nameToRestore(meta: ItemMeta, current: ItemStack, legacyName: String?): Component? {
        val standard = current.itemMeta ?: return null
        if (!standard.hasDisplayName()) return null
        val wanted = standard.displayName() ?: return null
        if (!meta.hasDisplayName()) return wanted
        val have = meta.displayName() ?: return wanted
        if (have == wanted) return null
        val havePlain = plain(have)
        if (havePlain == plain(wanted)) return wanted
        if (legacyName != null && havePlain == stripLegacy(legacyName)) return wanted
        return null
    }

    private fun restoreAttributes(meta: ItemMeta, modifiers: Multimap<Attribute, AttributeModifier>) {
        for ((attribute, modifier) in modifiers.entries()) {
            try {
                meta.addAttributeModifier(attribute, modifier)
            } catch (duplicate: IllegalArgumentException) {
                logger.fine("Skipped attribute modifier ${modifier.key}: ${duplicate.message}")
            }
        }
    }

    private fun restoreHeadProfile(
        target: SkullMeta,
        current: ItemStack,
        oldSkinUrl: String?,
        id: String,
    ): Boolean {
        var profile: PlayerProfile? = null
        val currentMeta = current.itemMeta
        if (currentMeta is SkullMeta && !CurrentLegacyDefinitionMatcher.isSkinlessHead(current)) {
            profile = currentMeta.ownerProfile
        }
        if (profile == null && oldSkinUrl != null) {
            profile = try {
                val created = Bukkit.createPlayerProfile(
                    UUID.nameUUIDFromBytes(id.toByteArray(StandardCharsets.UTF_8)),
                )
                val textures = created.getTextures()
                textures.setSkin(URL(oldSkinUrl))
                created.setTextures(textures)
                created
            } catch (_: MalformedURLException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
        }
        val owner = profile ?: return false
        target.ownerProfile = owner
        return true
    }

    fun isOfflineMigrationRunning(): Boolean = offlineMigrator?.isRunning() == true

    fun isFullWorldScanRunning(): Boolean = fullWorldScanner?.isRunning() == true

    private companion object {
        private const val PREFIX = "§6[MMID修復] §r"
        private const val PERM_USE = "mythicitemfixer.use"
        private const val PERM_ADMIN = "mythicitemfixer.admin"

        /** Subcommands every player may run on their own items. */
        private val PLAYER_SUBCOMMANDS = listOf("help", "hand", "inventory")

        /** Server-wide subcommands. */
        private val ADMIN_SUBCOMMANDS = listOf("status", "reload", "scan", "offline", "worldscan")

        /** Item lines shown per run; the rest are counted only. */
        private const val DETAIL_LIMIT = 12

        private fun send(sender: CommandSender, message: String) {
            sender.sendMessage(PREFIX + message)
        }

        private fun help(sender: CommandSender) {
            send(sender, "§fコマンド一覧")
            sender.sendMessage("§e/mmidfix hand §7… 手持ちアイテムのMMIDを確認・付与")
            sender.sendMessage(
                "§e/mmidfix inventory preview §7… 自分のインベントリとエンダーチェストの修復候補を表示（変更なし）",
            )
            sender.sendMessage("§e/mmidfix inventory §7… 自分のインベントリとエンダーチェストを修復")
            if (!sender.hasPermission(PERM_ADMIN)) return
            sender.sendMessage("§e/mmidfix status §7… 処理件数と旧定義の読込状況")
            sender.sendMessage("§e/mmidfix reload §7… 設定と旧定義を読み直す")
            sender.sendMessage("§e/mmidfix scan §7… オンラインプレイヤーと読込済みチャンクを走査")
            sender.sendMessage("§e/mmidfix offline §7… オフラインプレイヤーのファイルを走査")
            sender.sendMessage("§e/mmidfix worldscan §7… 全チャンクを順に読み込んで走査")
        }

        private fun matching(options: List<String>, typed: String): List<String> {
            val prefix = typed.lowercase(Locale.ROOT)
            return options.filter { it.startsWith(prefix) }
        }

        private fun errorCount(errors: Long): String = (if (errors > 0) "§c" else "§f") + errors + "§7件"

        /** Which of name / lore / custom_data the stack fix changes, for the preview line. */
        private fun stackableParts(before: ItemStack, after: ItemStack): String {
            val parts = ArrayList<String>()
            val a = before.itemMeta
            val b = after.itemMeta
            if (a.displayName() != b.displayName()) parts.add("名前の装飾")
            if (a.lore() != b.lore()) parts.add("Loreの装飾")
            if (a.persistentDataContainer.keys != b.persistentDataContainer.keys || parts.isEmpty()) {
                parts.add("MMデータ")
            }
            return parts.joinToString("・")
        }

        private fun slotLabel(enderChest: Boolean, slot: Int): String {
            if (enderChest) return "エンダーチェスト${slot + 1}"
            return when {
                slot < 9 -> "ホットバー${slot + 1}"
                slot < 36 -> "インベントリ${slot - 8}"
                slot < 40 -> arrayOf("足", "脚", "胴", "頭")[slot - 36]
                else -> "オフハンド"
            }
        }

        private fun plain(component: Component): String =
            stripLegacy(PlainTextComponentSerializer.plainText().serialize(component))

        private fun stripLegacy(text: String?): String {
            val stripped = ChatColor.stripColor(CurrentLegacyDefinitionMatcher.readableText(text))
            return stripped?.trim() ?: ""
        }
    }
}
