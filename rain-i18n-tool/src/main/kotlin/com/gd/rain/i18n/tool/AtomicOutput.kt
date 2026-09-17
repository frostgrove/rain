@file:Suppress("ktlint:standard:filename")

package com.gd.rain.i18n.tool

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

/**
 * Locked, same-directory atomic writer for one generated artifact.
 *
 * It refuses symlink output paths and lock files, forces staged bytes before replacement, and
 * never degrades a missing atomic move into a non-atomic copy. The caller supplies already
 * canonical bytes; this class owns only local publication integrity.
 */
public class AtomicOutputWriter {
    public fun replace(
        target: Path,
        content: ByteArray,
    ) {
        val normalized = target.toAbsolutePath().normalize()
        val parent = checkNotNull(normalized.parent) { "an output target has no parent directory" }
        prepareDirectory(parent)
        require(
            !Files.exists(normalized, NOFOLLOW_LINKS) ||
                (Files.isRegularFile(normalized, NOFOLLOW_LINKS) && !Files.isSymbolicLink(normalized)),
        ) {
            "an output target is not a regular non-symlink file"
        }
        val lock = parent.resolve(".${normalized.fileName}.rain-i18n.lock")
        require(
            !Files.exists(lock, NOFOLLOW_LINKS) ||
                (Files.isRegularFile(lock, NOFOLLOW_LINKS) && !Files.isSymbolicLink(lock)),
        ) {
            "an output lock is not a regular non-symlink file"
        }
        FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                writeLocked(normalized, content)
            }
        }
    }

    private fun writeLocked(
        target: Path,
        content: ByteArray,
    ) {
        val staged = Files.createTempFile(checkNotNull(target.parent), ".rain-i18n-", ".tmp")
        try {
            FileChannel.open(staged, WRITE, NOFOLLOW_LINKS).use { channel ->
                var bytes = ByteBuffer.wrap(content)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            try {
                Files.move(staged, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (failure: AtomicMoveNotSupportedException) {
                throw IllegalStateException("an atomic output replacement is unavailable", failure)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun prepareDirectory(path: Path) {
        path.parent?.let(::prepareDirectory)
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "an output parent is not a directory or is a symlink"
        }
    }
}
