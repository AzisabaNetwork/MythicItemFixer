package net.azisaba.mythicitemFixer

import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.scheduler.BukkitTask
import java.io.DataInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.ArrayDeque
import java.util.logging.Level

class FullWorldScanner(
    private val plugin: MythicItemFixer,
    private val listener: CompatibilityListener,
) {
    private val regions = ArrayDeque<RegionRef>()
    private val chunks = ArrayDeque<ChunkRef>()
    private var task: BukkitTask? = null
    private var regionsChecked = 0
    private var chunksChecked = 0
    private var converted = 0
    private var errors = 0

    fun start(completion: (String) -> Unit): Boolean {
        if (task != null) return false
        regions.clear()
        chunks.clear()
        regionsChecked = 0
        chunksChecked = 0
        converted = 0
        errors = 0
        for (world in Bukkit.getWorlds()) {
            val directory = world.worldFolder.toPath().resolve("region")
            if (!Files.isDirectory(directory)) continue
            try {
                Files.newDirectoryStream(directory, "r.*.*.mca").use { files ->
                    for (file in files) {
                        val matcher = REGION_NAME.matchEntire(file.fileName.toString()) ?: continue
                        regions.offer(
                            RegionRef(
                                world,
                                file,
                                matcher.groupValues[1].toInt(),
                                matcher.groupValues[2].toInt(),
                            ),
                        )
                    }
                }
            } catch (exception: IOException) {
                errors++
                plugin.logger.log(Level.WARNING, "Could not list $directory", exception)
            }
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick(completion) }, 1L, 1L)
        return true
    }

    fun isRunning(): Boolean = task != null

    private fun tick(completion: (String) -> Unit) {
        val limit = maxOf(1, plugin.config.getInt("full-world-chunks-per-tick", 1))
        var index = 0
        while (index < limit) {
            if (chunks.isEmpty()) {
                val region = regions.poll() ?: run {
                    task?.cancel()
                    task = null
                    completion(status("ワールド全体の走査が完了しました"))
                    return
                }
                readRegionHeader(region)
                regionsChecked++
                if (chunks.isEmpty()) continue
            }
            val ref = chunks.poll() ?: continue
            val wasLoaded = ref.world.isChunkLoaded(ref.x, ref.z)
            try {
                val chunk = ref.world.getChunkAt(ref.x, ref.z)
                converted += listener.scanChunk(chunk)
                chunksChecked++
                // Entities arrive a little after the chunk (EntitiesLoadEvent converts them): unload later
                if (!wasLoaded) {
                    Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                        if (ref.world.isChunkLoaded(ref.x, ref.z)) ref.world.unloadChunk(ref.x, ref.z, true)
                    }, ENTITY_LOAD_GRACE_TICKS)
                }
            } catch (exception: RuntimeException) {
                errors++
                if (errors <= 10) {
                    plugin.logger.log(
                        Level.WARNING,
                        "Could not scan chunk ${ref.x},${ref.z} in ${ref.world.name}",
                        exception,
                    )
                }
            }
            index++
        }
    }

    private fun readRegionHeader(region: RegionRef) {
        try {
            DataInputStream(Files.newInputStream(region.path)).use { input ->
                for (index in 0 until 1024) {
                    if (input.readInt() != 0) {
                        chunks.offer(
                            ChunkRef(
                                region.world,
                                region.regionX * 32 + index % 32,
                                region.regionZ * 32 + index / 32,
                            ),
                        )
                    }
                }
            }
        } catch (exception: IOException) {
            errors++
            if (errors <= 10) plugin.logger.log(Level.WARNING, "Could not read region header ${region.path}", exception)
        }
    }

    fun status(prefix: String): String =
        "$prefix：リージョン ${regionsChecked}件・チャンク ${chunksChecked}件を確認、ID付与 " +
            "${converted}個、残りリージョン ${regions.size}件、エラー ${errors}件"

    private data class RegionRef(val world: World, val path: Path, val regionX: Int, val regionZ: Int)

    private data class ChunkRef(val world: World, val x: Int, val z: Int)

    companion object {
        private const val ENTITY_LOAD_GRACE_TICKS = 40L
        private val REGION_NAME = Regex("r\\.(-?\\d+)\\.(-?\\d+)\\.mca")
    }
}
