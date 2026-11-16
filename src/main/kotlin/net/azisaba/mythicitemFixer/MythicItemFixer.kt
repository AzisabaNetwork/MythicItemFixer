package net.azisaba.mythicitemFixer

import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import io.papermc.paper.datacomponent.DataComponentTypes
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Chunk
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeModifier
import org.bukkit.block.Container
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
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
        reloadExclusions()
        // MythicMobs may finish loading its items after this plugin is enabled: look again next tick
        Bukkit.getScheduler().runTask(this, Runnable { reloadExclusions() })
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
        if (outcome is RepairOutcome.Reverted) return outcome.item
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
        val sub = when (val first = args.firstOrNull()?.lowercase(Locale.ROOT)) {
            null, "fix" -> "fix"
            // old spellings
            "inventory", "inv" -> if (args.getOrNull(1).equals("preview", ignoreCase = true)) "check" else "fix"
            "preview" -> "check"
            else -> first
        }
        if (if (sub in ADMIN_SUBCOMMANDS) !sender.hasPermission(PERM_ADMIN) else !sender.hasPermission(PERM_USE)) {
            send(sender, "§cこのコマンドを実行する権限がありません。")
            return true
        }
        when (sub) {
            "fix" -> asPlayer(sender)?.let { repairInventory(it, preview = false, automatic = false) }
            "check" -> asPlayer(sender)?.let { repairInventory(it, preview = true, automatic = false) }
            "hand" -> asPlayer(sender)?.let(::inspectHand)
            "status" -> status(sender)
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
        if (args.size != 1) return emptyList()
        val options = ArrayList<String>()
        if (sender.hasPermission(PERM_USE)) options.addAll(PLAYER_SUBCOMMANDS)
        if (sender.hasPermission(PERM_ADMIN)) options.addAll(ADMIN_SUBCOMMANDS)
        return matching(options, args[0])
    }

    private fun asPlayer(sender: CommandSender): Player? {
        if (sender is Player) return sender
        send(sender, "§eプレイヤーとして実行してください。")
        return null
    }

    private fun status(sender: CommandSender) {
        val itemConverter = converter ?: return
        send(sender, "§f動作状況")
        sender.sendMessage(
            "§7・自動処理：確認 §f${itemConverter.getChecked()}§7個 / 旧タグからID付与 §a" +
                "${itemConverter.getConverted()}§7個 / 重なるよう整形 §a${itemConverter.getNormalized()}§7個 / " +
                "バニラに戻す §a${itemConverter.getReverted()}§7個 / " +
                "除外 §f${itemConverter.getExcluded()}§7個 / エラー " + errorCount(itemConverter.getErrors()),
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

    private fun legacyMissing(player: Player) {
        send(player, "§c旧MythicMobsのItemsを読み込めていないため修復できません。")
        player.sendMessage("§7plugins/MythicItemFixer/legacy-mythic-items/ に1.15.2のItemsの中身を入れて §e/mmidfix reload")
        player.sendMessage("§7詳細：$legacyMatcherError")
    }

    /** `/mmidfix hand`: repairs the held item and explains everything that still differs from `/mm i get`. */
    private fun inspectHand(player: Player) {
        val matcher = legacyMatcher ?: return legacyMissing(player)
        val itemConverter = converter ?: return
        val item = player.inventory.itemInMainHand
        if (item.type.isAir) {
            send(player, "§e手に何も持っていません。")
            return
        }
        val name = itemName(item)
        val box = storageBoxBridge?.repair(item)
        if (box != null) {
            player.inventory.setItemInMainHand(box)
            send(player, "§f$name§7：§aStorage Boxの中身を修復しました。")
            return
        }
        val contents = repairContents(item, matcher, itemConverter, 0)
        if (contents != null) {
            player.inventory.setItemInMainHand(contents.item)
            send(player, "§f$name§7：§a中のアイテム ${contents.count}個を修復しました。")
            return
        }
        when (val outcome = repairOne(item, matcher, itemConverter)) {
            RepairOutcome.NotMythic -> send(
                player,
                "§f$name§7：MMIDが無く、旧定義・今の定義のどれとも一致しないためMMアイテムと判断できません。",
            )
            is RepairOutcome.Excluded -> send(
                player,
                "§f$name§7：MMID §f${outcome.id}§7 は" + if (exclusions.isVanillaDefinition(outcome.id)) {
                    "今のMMの定義がただのバニラアイテムなので、置き換えません。"
                } else {
                    "除外設定されています。"
                },
            )
            is RepairOutcome.Reverted -> {
                player.inventory.setItemInMainHand(outcome.item)
                send(
                    player,
                    "§f$name§7：MMID §f${outcome.id}§7 の今の定義はただのバニラアイテムなので、" +
                        "§aMMのタグを外して普通のアイテムに戻しました。",
                )
            }
            is RepairOutcome.MissingCurrent -> send(
                player,
                "§f$name§7：MMID §f${outcome.id}§7 は今のMMに無いため修復できません。",
            )
            is RepairOutcome.Done -> {
                val result = outcome.result
                if (result.changed) player.inventory.setItemInMainHand(result.item)
                send(player, "§f$name §7（MMID §a${result.id}§7）")
                player.sendMessage(
                    if (result.changed) "§a ✔ 修復：" + fixSummary(result) else "§7 ・修復が必要な箇所はありませんでした",
                )
                when {
                    !result.canStack -> player.sendMessage("§7 ・重ならない種類のアイテムです")
                    result.stacks -> player.sendMessage("§a ✔ /mm i get のアイテムと重なります")
                    else -> {
                        player.sendMessage("§e △ /mm i get のアイテムと重なりません")
                        for (reason in result.stackReasons) player.sendMessage("§7   ・$reason")
                        val template = itemConverter.normalizer.template(result.id)
                        if (template != null) showComponentDiff(player, result.item, template)
                    }
                }
            }
        }
    }

    /** Prints each differing component with both values, shortened, for finding why two items differ. */
    private fun showComponentDiff(player: Player, item: ItemStack, template: ItemStack) {
        val mine = StackNormalizer.components(item)
        val wanted = StackNormalizer.components(template)
        val keys = StackNormalizer.differingComponents(item, template)
        if (keys.isEmpty()) return
        player.sendMessage("§7 違う部分（§f手持ち §7/ §b今の定義§7）：")
        for (key in keys.take(8)) {
            player.sendMessage("§7  ${StackNormalizer.label(key)} §8($key)")
            player.sendMessage("§f    " + shorten(mine[key]?.toString() ?: "なし"))
            player.sendMessage("§b    " + shorten(wanted[key]?.toString() ?: "なし"))
        }
        if (keys.size > 8) player.sendMessage("§7  …ほか ${keys.size - 8}件")
    }

    private class Report(private val player: Player, private val show: Boolean) {
        var inspected = 0
        var repaired = 0
        var notStacking = 0
        var missingCurrent = 0
        var skinlessGuesses = 0
        private var lines = 0
        private var hidden = 0

        fun line(text: String) {
            if (!show) return
            if (lines < DETAIL_LIMIT) {
                player.sendMessage(text)
                lines++
            } else {
                hidden++
            }
        }

        fun finishLines() {
            if (hidden > 0) player.sendMessage("§7 …ほか ${hidden}件")
        }
    }

    /**
     * Repairs (or with [preview] only reports) every item in the inventory and ender chest, including
     * shulker box / bundle / Storage Box contents.
     */
    private fun repairInventory(player: Player, preview: Boolean, automatic: Boolean) {
        val matcher = legacyMatcher ?: run {
            if (!automatic) legacyMissing(player)
            return
        }
        val itemConverter = converter ?: return
        val report = Report(player, !automatic)
        if (!automatic) {
            send(
                player,
                if (preview) {
                    "§fインベントリとエンダーチェストを確認しました §e（確認のみ・変更していません）"
                } else {
                    "§fインベントリとエンダーチェストを修復しました"
                },
            )
        }
        val inventories = arrayOf(player.inventory, player.enderChest)
        for (pass in inventories.indices) {
            val inventory = inventories[pass]
            for (slot in 0 until inventory.size) {
                val item = inventory.getItem(slot) ?: continue
                if (item.type.isAir || !item.hasItemMeta()) continue
                report.inspected++
                val place = "§7[${slotLabel(pass == 1, slot)}] §f${itemName(item)}"
                // A Storage Box keeps its content outside the inventory; the box item is not a Mythic item.
                val box = storageBoxBridge?.repair(item)
                if (box != null) {
                    if (!preview) inventory.setItem(slot, box)
                    report.repaired++
                    report.line("§a ✔ $place §7… Storage Boxの中身を修復")
                    continue
                }
                val contents = repairContents(item, matcher, itemConverter, 0)
                if (contents != null) {
                    if (!preview) inventory.setItem(slot, contents.item)
                    report.repaired++
                    report.line("§a ✔ $place §7… 中のアイテム ${contents.count}個を修復")
                    continue
                }
                when (val outcome = repairOne(item, matcher, itemConverter)) {
                    RepairOutcome.NotMythic, is RepairOutcome.Excluded -> Unit
                    is RepairOutcome.Reverted -> {
                        if (!preview) inventory.setItem(slot, outcome.item)
                        report.repaired++
                        report.line("§a ✔ $place §7… §aバニラアイテムに戻す §7（${outcome.id} の定義がバニラ）")
                    }
                    is RepairOutcome.MissingCurrent -> {
                        report.missingCurrent++
                        report.line("§c ✘ $place §7… MMID ${outcome.id} が今のMMにありません")
                    }
                    is RepairOutcome.Done -> {
                        val result = outcome.result
                        if (result.inferredWithoutSkin) report.skinlessGuesses++
                        val stuck = result.canStack && !result.stacks
                        if (stuck) report.notStacking++
                        if (result.changed) {
                            if (!preview) inventory.setItem(slot, result.item)
                            report.repaired++
                            report.line(
                                "§a ✔ $place §7… " + fixSummary(result) +
                                    (if (result.inferredWithoutSkin) " §e（スキン無し・名前とLoreで推定）" else "") +
                                    (if (stuck) " §e※まだ重なりません" else ""),
                            )
                        } else if (stuck) {
                            report.line("§e △ $place §7… 重なりません：" + result.stackReasons.joinToString("、"))
                        }
                    }
                }
            }
        }
        if (automatic) {
            if (report.repaired > 0) {
                send(player, "§aインベントリのMMアイテムを ${report.repaired}個 自動修復しました §7（確認は /mmidfix check）")
            }
            return
        }
        report.finishLines()
        player.sendMessage(
            "§f" + (if (preview) "修復できるもの" else "修復") + " §a${report.repaired}§f個 §7/ " +
                "§f重ならないもの §e${report.notStacking}§f個 §7/ 確認したアイテム ${report.inspected}個",
        )
        if (report.missingCurrent > 0) {
            player.sendMessage("§c今のMMに無いIDのアイテムが ${report.missingCurrent}個あります。§7MM側の定義を確認してください。")
        }
        if (report.skinlessGuesses > 0) {
            player.sendMessage("§eスキンが失われたヘッド ${report.skinlessGuesses}個は名前とLoreから推定しています。")
        }
        when {
            report.repaired == 0 && report.notStacking == 0 && report.missingCurrent == 0 ->
                player.sendMessage("§a修復が必要なアイテムはありません。")
            preview && report.repaired > 0 -> player.sendMessage("§7修復するには §e/mmidfix")
        }
        if (report.notStacking > 0) {
            player.sendMessage("§7重ならない理由の詳細は、そのアイテムを手に持って §e/mmidfix hand")
        }
    }

    private class ContentRepair(val item: ItemStack, val count: Int)

    /**
     * A copy of a shulker box or bundle with the items inside repaired (recursively), or null when
     * nothing inside changes. [item] is not modified.
     */
    @Suppress("UnstableApiUsage")
    private fun repairContents(
        item: ItemStack,
        matcher: CurrentLegacyDefinitionMatcher,
        itemConverter: ItemConverter,
        depth: Int,
    ): ContentRepair? {
        if (depth > 4) return null
        if (!item.hasData(DataComponentTypes.CONTAINER) && !item.hasData(DataComponentTypes.BUNDLE_CONTENTS)) {
            return null
        }
        val copy = item.clone()
        val meta = copy.itemMeta ?: return null
        var count = 0
        if (meta is BlockStateMeta && meta.hasBlockState()) {
            val state = meta.blockState as? Container ?: return null
            val inventory = state.inventory
            for (slot in 0 until inventory.size) {
                val nested = inventory.getItem(slot) ?: continue
                val repaired = repairNested(nested, matcher, itemConverter, depth) ?: continue
                inventory.setItem(slot, repaired.item)
                count += repaired.count
            }
            if (count == 0) return null
            meta.blockState = state
        } else if (meta is BundleMeta && meta.hasItems()) {
            val items = meta.items.map { nested ->
                val repaired = repairNested(nested, matcher, itemConverter, depth)
                if (repaired != null) count += repaired.count
                repaired?.item ?: nested
            }
            if (count == 0) return null
            meta.setItems(items)
        } else {
            return null
        }
        copy.itemMeta = meta
        return ContentRepair(copy, count)
    }

    private fun repairNested(
        nested: ItemStack,
        matcher: CurrentLegacyDefinitionMatcher,
        itemConverter: ItemConverter,
        depth: Int,
    ): ContentRepair? {
        if (nested.type.isAir || !nested.hasItemMeta()) return null
        repairContents(nested, matcher, itemConverter, depth + 1)?.let { return it }
        storageBoxBridge?.repair(nested)?.let { return ContentRepair(it, 1) }
        val outcome = repairOne(nested, matcher, itemConverter)
        if (outcome is RepairOutcome.Reverted) return ContentRepair(outcome.item, 1)
        val result = (outcome as? RepairOutcome.Done)?.result ?: return null
        return if (result.changed) ContentRepair(result.item, 1) else null
    }

    private fun fixSummary(result: RepairResult): String {
        val fixes = ArrayList<String>()
        if (result.needsId) fixes.add("MMID付与（${result.id}）")
        if (result.model != null) fixes.add("Model復元")
        if (result.skinRestored) fixes.add("スキン復元")
        if (result.attributesRestored > 0) fixes.add("属性${result.attributesRestored}件復元")
        if (result.nameRestored) fixes.add("名前復元")
        if (result.restacked) {
            val parts = StackNormalizer.differingComponents(result.fixed, result.item).map(StackNormalizer::label)
            fixes.add(
                "重なるよう整形" + if (parts.isEmpty()) "" else "（" + parts.take(4).joinToString("・") +
                    (if (parts.size > 4) "…" else "") + "）",
            )
        }
        return "§a" + fixes.joinToString("§7、§a")
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
        var id = meta.persistentDataContainer.get(ItemConverter.TYPE, PersistentDataType.STRING)?.trim()
        val needsId = id.isNullOrEmpty()
        if (needsId) {
            // MythicMobs 4 MYTHIC_TYPE tag first, then the 1.15.2 / current definition match
            id = itemConverter.bridge.read(item.clone())?.mythicType?.trim()?.takeIf { it.isNotEmpty() }
                ?: matcher.match(item)
        }
        if (id == null) return RepairOutcome.NotMythic
        ItemConverter.vanillaOf(item, id, exclusions)?.let { return RepairOutcome.Reverted(id, it) }
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
        val analysis = itemConverter.normalizer.analyze(fixed, id, legacyName, matcher.legacyLoreFor(id))
        val stackable = analysis.result
        return RepairOutcome.Done(
            RepairResult(
                id = id,
                item = stackable ?: fixed,
                fixed = fixed,
                needsId = needsId,
                model = model,
                skinRestored = skinRestored,
                attributesRestored = missingAttributes.size(),
                nameRestored = wantedName != null,
                restacked = stackable != null,
                inferredWithoutSkin = inferredWithoutSkin,
                canStack = analysis.canStack,
                stacks = analysis.stacks,
                stackReasons = analysis.reasons,
            ),
        )
    }

    private sealed interface RepairOutcome {
        object NotMythic : RepairOutcome
        data class Excluded(val id: String) : RepairOutcome
        data class MissingCurrent(val id: String) : RepairOutcome

        /** The MMID's definition is a plain vanilla item: [item] is that vanilla item without MM tags. */
        class Reverted(val id: String, val item: ItemStack) : RepairOutcome
        data class Done(val result: RepairResult) : RepairOutcome
    }

    private class RepairResult(
        val id: String,
        val item: ItemStack,
        val fixed: ItemStack,
        val needsId: Boolean,
        val model: Int?,
        val skinRestored: Boolean,
        val attributesRestored: Int,
        val nameRestored: Boolean,
        val restacked: Boolean,
        val inferredWithoutSkin: Boolean,
        /** Whether the item can stack at all (max stack size > 1). */
        val canStack: Boolean,
        /** Whether [item] stacks with `/mm i get`. */
        val stacks: Boolean,
        /** Why [item] still does not stack with `/mm i get`. */
        val stackReasons: List<String>,
    ) {
        val changed: Boolean
            get() = needsId || model != null || skinRestored || attributesRestored > 0 || nameRestored || restacked
    }

    private fun reloadExclusions() {
        var loaded = MmidExclusions.parse(config.getStringList("excluded-mmids"))
        val itemConverter = converter
        if (itemConverter != null && config.getBoolean("exclude-vanilla-definitions", true)) {
            loaded = loaded.withVanillaDefinitions(vanillaDefinitions(itemConverter.bridge))
        }
        exclusions = loaded
        logger.info("Excluded MMIDs: ${exclusions.summary()}")
    }

    /** IDs whose current definition generates a plain vanilla item (see [MmidExclusions]). */
    private fun vanillaDefinitions(bridge: MythicItemBridge): List<String> {
        val ids = ArrayList<String>()
        for (id in bridge.allIds()) {
            val item = bridge.currentItem(id) ?: continue
            try {
                if (StackNormalizer.isVanillaEquivalent(item)) ids.add(id)
            } catch (_: RuntimeException) {
                // unreadable definition: leave it to the normal rules
            }
        }
        return ids
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
            itemConverter.setLegacyMatcher(loaded)
            logger.info("Legacy item definitions loaded: ${loaded.summary()}")
        } catch (error: IOException) {
            legacyMatcher = null
            legacyMatcherError = error.message
            itemConverter.setLegacyMatcher(null)
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
        private val PLAYER_SUBCOMMANDS = listOf("check", "hand", "help")

        /** Server-wide subcommands. */
        private val ADMIN_SUBCOMMANDS = listOf("status", "reload", "scan", "offline", "worldscan")

        /** Item lines shown per run; the rest are counted only. */
        private const val DETAIL_LIMIT = 12

        /** Characters of a component value shown by `/mmidfix hand`. */
        private const val SHORTEN_LIMIT = 120

        private fun send(sender: CommandSender, message: String) {
            sender.sendMessage(PREFIX + message)
        }

        private fun help(sender: CommandSender) {
            send(sender, "§fコマンド一覧")
            sender.sendMessage(
                "§e/mmidfix §7… インベントリとエンダーチェストのMMアイテムを修復" +
                    "（シュルカー・バンドル・Storage Boxの中身も）",
            )
            sender.sendMessage("§e/mmidfix check §7… 何が修復されるか・重ならないアイテムを確認だけする（変更なし）")
            sender.sendMessage("§e/mmidfix hand §7… 手持ちのアイテムを修復し、/mm i get と違う部分を詳しく表示")
            if (!sender.hasPermission(PERM_ADMIN)) return
            sender.sendMessage("§6管理者用")
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

        private fun shorten(text: String): String =
            if (text.length <= SHORTEN_LIMIT) text else text.substring(0, SHORTEN_LIMIT) + "…"

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
