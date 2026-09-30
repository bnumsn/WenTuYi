package com.wentuyi.cli

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Stable lock files are never replaced or removed, including when a session is reset. */
internal object SecretFiles {
    private val localLocks = ConcurrentHashMap<Path, ReentrantLock>()

    private fun attributes(path: Path, mode: String) =
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(mode)))
        } else emptyArray()

    fun createDirectories(path: Path) {
        Files.createDirectories(path, *attributes(path, "rwx------"))
    }

    fun <T> withLock(lockPath: Path, block: () -> T): T {
        val absolute = lockPath.toAbsolutePath().normalize()
        createDirectories(absolute.parent)
        // Resolve directory aliases so two Profile instances in one JVM share a mutex.
        val canonical = absolute.parent.toRealPath().resolve(absolute.fileName)
        val mutex = localLocks.computeIfAbsent(canonical) { ReentrantLock() }
        return mutex.withLock mutexBlock@{
            // Public profile methods nest inside a command transaction.
            if (mutex.holdCount > 1) return@mutexBlock block()
            FileChannel.open(canonical, setOf(CREATE, WRITE), *attributes(canonical, "rw-------")).use { channel ->
                channel.lock().use { block() }
            }
        }
    }

    fun <T> withStateLock(statePath: Path, block: () -> T): T {
        val absolute = statePath.toAbsolutePath().normalize()
        // Raw commands may be pointed at a profile's peers/*.ratchet file. They must
        // participate in the same transaction as send/receive and identity management.
        val lock = if (absolute.parent.fileName?.toString() == "peers") {
            absolute.parent.parent.resolve(".lock")
        } else absolute.resolveSibling(".${absolute.fileName}.lock")
        return withLock(lock, block)
    }

    /** Fail closed if atomic replacement is unsupported; never truncate the live state. */
    fun write(path: Path, content: String) {
        val target = path.toAbsolutePath().normalize()
        createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}.", ".tmp",
            *attributes(target, "rw-------"))
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
            syncDirectory(target.parent)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun delete(path: Path) {
        if (Files.deleteIfExists(path)) syncDirectory(path.toAbsolutePath().parent)
    }

    private fun syncDirectory(path: Path) {
        // Windows does not support opening a directory as a FileChannel. On POSIX, force
        // the rename/deletion too: a power loss must not revive an emitted message key.
        if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
            FileChannel.open(path, READ).use { it.force(true) }
        }
    }
}
