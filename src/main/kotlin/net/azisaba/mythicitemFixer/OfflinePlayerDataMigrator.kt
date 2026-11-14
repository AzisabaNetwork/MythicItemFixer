package net.azisaba.mythicitemFixer

import org.bukkit.Bukkit
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.HashSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class OfflinePlayerDataMigrator(private val plugin: MythicItemFixer) {
    private val running = AtomicBoolean()

    fun start(completion: (Result) -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        val online = Bukkit.getOnlinePlayers().mapTo(HashSet()) { it.uniqueId }
        val directories = Bukkit.getWorlds().mapTo(HashSet()) { it.worldFolder.toPath().resolve("playerdata") }
        val exclusions = plugin.exclusions
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val result = Result()
            try {
                for (directory in directories) scanDirectory(directory, online, result, exclusions)
            } finally {
                running.set(false)
                Bukkit.getScheduler().runTask(plugin, Runnable { completion(result) })
            }
        })
        return true
    }

    fun isRunning(): Boolean = running.get()

    private fun scanDirectory(
        directory: Path,
        online: Set<UUID>,
        result: Result,
        exclusions: MmidExclusions,
    ) {
        if (!Files.isDirectory(directory)) return
        try {
            Files.newDirectoryStream(directory, "*.dat").use { files ->
                for (file in files) {
                    result.filesChecked++
                    val name = file.fileName.toString()
                    try {
                        val uuid = UUID.fromString(name.substring(0, name.length - 4))
                        if (uuid in online) {
                            result.onlineSkipped++
                            continue
                        }
                    } catch (_: IllegalArgumentException) {
                        // Non-standard player file names are still safe to inspect as NBT.
                    }
                    try {
                        val changed = convertFile(file, exclusions)
                        if (changed > 0) {
                            result.filesChanged++
                            result.itemsChanged += changed
                        }
                    } catch (exception: Exception) {
                        result.errors++
                        if (result.errors <= 10) {
                            plugin.logger.log(Level.WARNING, "Could not migrate offline player data $file", exception)
                        }
                    }
                }
            }
        } catch (exception: IOException) {
            result.errors++
            plugin.logger.log(Level.WARNING, "Could not list offline player data in $directory", exception)
        }
    }

    @Throws(IOException::class)
    private fun convertFile(file: Path, exclusions: MmidExclusions): Int {
        val root = DataInputStream(
            BufferedInputStream(GZIPInputStream(Files.newInputStream(file))),
        ).use { NbtIo.readRoot(it) }
        val changed = OfflineNbtItemConverter.convert(root, exclusions)
        if (changed == 0) return 0

        val backup = file.resolveSibling("${file.fileName}.mmidcompat.bak")
        if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES)
        val temporary = file.resolveSibling("${file.fileName}.mmidcompat.tmp")
        try {
            DataOutputStream(
                BufferedOutputStream(GZIPOutputStream(Files.newOutputStream(temporary))),
            ).use { NbtIo.writeRoot(it, root) }
            try {
                Files.move(
                    temporary,
                    file,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        return changed
    }

    class Result {
        var filesChecked = 0
        var filesChanged = 0
        var itemsChanged = 0
        var onlineSkipped = 0
        var errors = 0

        fun message(): String =
            "オフラインプレイヤーの変換が完了しました：ファイル ${filesChecked}件を確認、" +
                "${filesChanged}件を更新（ID付与 ${itemsChanged}個）、ログイン中のため省略 " +
                "${onlineSkipped}件、エラー ${errors}件"
    }
}
