package com.gd.rain.i18n.tool

import com.gd.rain.i18n.Digest
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Finite local ceilings for public contract publication and untrusted pointer reads. */
public data class TypeScriptPublicationLimits(
    public val maxFileBytes: Int = DEFAULT_MAX_FILE_BYTES,
) {
    init {
        require(maxFileBytes in 1..MAX_FILE_BYTES) { "a TypeScript publication file ceiling is 1..$MAX_FILE_BYTES bytes" }
    }

    public companion object {
        public const val DEFAULT_MAX_FILE_BYTES: Int = 4 * 1024 * 1024
        public const val MAX_FILE_BYTES: Int = 64 * 1024 * 1024
    }
}

/** A successful pointer update identifies the immutable content-addressed generation. */
public data class TypeScriptPublicationResult(
    public val generation: Digest,
)

/** A writer reports exact publication success or a bounded operational refusal. */
public sealed interface TypeScriptPublicationWrite {
    public data class Published(
        public val result: TypeScriptPublicationResult,
    ) : TypeScriptPublicationWrite

    public data class Refused(
        public val reason: TypeScriptPublicationRefusal,
    ) : TypeScriptPublicationWrite
}

/** A reader either pins one fully verified generation or returns a closed refusal. */
public sealed interface TypeScriptPublicationRead {
    public data class Loaded(
        public val export: TypeScriptExport,
    ) : TypeScriptPublicationRead

    public data object Missing : TypeScriptPublicationRead

    public data class Refused(
        public val reason: TypeScriptPublicationRefusal,
    ) : TypeScriptPublicationRead
}

/** Publication failures are intentionally path-free, so a CLI can surface them safely. */
public enum class TypeScriptPublicationRefusal {
    UNSAFE_PATH,
    POINTER_INVALID,
    GENERATION_INVALID,
    FILE_LIMIT,
    ATOMIC_MOVE_UNAVAILABLE,
    IO_FAILURE,
}

/**
 * Crash-safe content-addressed writer and reader for public TypeScript artifacts.
 *
 * Content files are forced into a new immutable generation before an atomic replacement of the
 * one pointer. The reader reads that pointer once, accepts the fixed role/name pair only, and
 * verifies size and digest before returning bytes to the caller.
 */
public class TypeScriptPublication(
    private val limits: TypeScriptPublicationLimits = TypeScriptPublicationLimits(),
) {
    public fun publish(
        root: Path,
        export: TypeScriptExport,
    ): TypeScriptPublicationWrite =
        try {
            val rootDirectory = prepareRoot(root)
            val declarations = export.declarationsBytes()
            val manifest = export.manifestBytes()
            if (declarations.size > limits.maxFileBytes || manifest.size > limits.maxFileBytes) {
                return TypeScriptPublicationWrite.Refused(TypeScriptPublicationRefusal.FILE_LIMIT)
            }
            openLock(rootDirectory).use { lock ->
                lock.lock().use {
                    val generation = export.digest
                    val generationName = generationName(generation)
                    val generations = prepareDirectory(rootDirectory.resolve(GENERATIONS))
                    val target = generations.resolve(generationName)
                    if (Files.exists(target, NOFOLLOW_LINKS)) {
                        val verified = verifyGeneration(target, generation)
                        if (verified !is TypeScriptPublicationRead.Loaded) {
                            return TypeScriptPublicationWrite.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
                        }
                    } else {
                        writeGeneration(generations, target, declarations, manifest)
                    }
                    writePointer(rootDirectory, generation, declarations, manifest)
                    TypeScriptPublicationWrite.Published(TypeScriptPublicationResult(generation))
                }
            }
        } catch (failure: TypeScriptPublicationFailure) {
            TypeScriptPublicationWrite.Refused(failure.reason)
        } catch (_: AtomicMoveNotSupportedException) {
            TypeScriptPublicationWrite.Refused(TypeScriptPublicationRefusal.ATOMIC_MOVE_UNAVAILABLE)
        } catch (_: IOException) {
            TypeScriptPublicationWrite.Refused(TypeScriptPublicationRefusal.IO_FAILURE)
        } catch (_: SecurityException) {
            TypeScriptPublicationWrite.Refused(TypeScriptPublicationRefusal.UNSAFE_PATH)
        }

    public fun read(root: Path): TypeScriptPublicationRead =
        try {
            val rootDirectory = existingRoot(root) ?: return TypeScriptPublicationRead.Missing
            val pointer = rootDirectory.resolve(POINTER)
            if (!Files.exists(pointer, NOFOLLOW_LINKS)) return TypeScriptPublicationRead.Missing
            val pointerDocument =
                decode(readFile(pointer, POINTER_LIMIT_BYTES))
                    ?: return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.POINTER_INVALID)
            val parsed =
                parsePointer(pointerDocument) ?: return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.POINTER_INVALID)
            val generation = rootDirectory.resolve(GENERATIONS).resolve(parsed.generation)
            if (generation.fileName.toString() !=
                parsed.generation
            ) {
                return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.UNSAFE_PATH)
            }
            val loaded = verifyGeneration(generation, Digest.parse(parsed.generation.removePrefix(GENERATION_PREFIX)))
            if (loaded !is TypeScriptPublicationRead.Loaded) return loaded
            val export = loaded.export
            if (export.declarationsBytes().size != parsed.declarationsSize ||
                Digest.sha256(export.declarationsBytes()) != parsed.declarationsDigest
            ) {
                return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
            }
            if (export.manifestBytes().size != parsed.manifestSize || Digest.sha256(export.manifestBytes()) != parsed.manifestDigest) {
                return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
            }
            loaded
        } catch (_: IllegalArgumentException) {
            TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.POINTER_INVALID)
        } catch (_: IOException) {
            TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.IO_FAILURE)
        } catch (_: SecurityException) {
            TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.UNSAFE_PATH)
        }

    private fun writeGeneration(
        generations: Path,
        target: Path,
        declarations: ByteArray,
        manifest: ByteArray,
    ) {
        val staging = generations.resolve(".staging-${UUID.randomUUID()}")
        try {
            Files.createDirectory(staging)
            requireDirectory(staging)
            writeFile(staging.resolve(DECLARATIONS), declarations)
            writeFile(staging.resolve(MANIFEST), manifest)
            forceDirectory(staging)
            moveAtomically(staging, target)
            forceDirectory(generations)
        } catch (failure: Throwable) {
            deleteStaging(staging)
            throw failure
        }
    }

    private fun writePointer(
        root: Path,
        generation: Digest,
        declarations: ByteArray,
        manifest: ByteArray,
    ) {
        val content = pointer(generation, declarations, manifest).toByteArray(StandardCharsets.UTF_8)
        val staging = root.resolve(".pointer-${UUID.randomUUID()}")
        try {
            writeFile(staging, content)
            moveAtomically(staging, root.resolve(POINTER), replace = true)
            forceDirectory(root)
        } catch (failure: Throwable) {
            deleteStaging(staging)
            throw failure
        }
    }

    private fun verifyGeneration(
        directory: Path,
        expected: Digest,
    ): TypeScriptPublicationRead {
        if (!isDirectory(directory)) return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
        val names = Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
        if (names !=
            listOf(DECLARATIONS, MANIFEST)
        ) {
            return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
        }
        val declarations =
            readFile(directory.resolve(DECLARATIONS), limits.maxFileBytes)
                ?: return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
        val manifest =
            readFile(directory.resolve(MANIFEST), limits.maxFileBytes)
                ?: return TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
        val export = TypeScriptExport(declarations, manifest)
        return if (export.digest == expected) {
            TypeScriptPublicationRead.Loaded(export)
        } else {
            TypeScriptPublicationRead.Refused(TypeScriptPublicationRefusal.GENERATION_INVALID)
        }
    }

    private fun prepareRoot(path: Path): Path = prepareDirectory(path.toAbsolutePath().normalize())

    private fun existingRoot(path: Path): Path? {
        val normalized = path.toAbsolutePath().normalize()
        if (!Files.exists(normalized, NOFOLLOW_LINKS)) return null
        requireDirectory(normalized)
        return normalized
    }

    private fun prepareDirectory(path: Path): Path {
        val parent = path.parent
        if (parent != null && !Files.exists(path, NOFOLLOW_LINKS)) prepareDirectory(parent)
        if (!Files.exists(path, NOFOLLOW_LINKS)) Files.createDirectory(path)
        requireDirectory(path)
        return path
    }

    private fun requireDirectory(path: Path) {
        if (!isDirectory(path)) throw TypeScriptPublicationFailure(TypeScriptPublicationRefusal.UNSAFE_PATH)
    }

    private fun isDirectory(path: Path): Boolean = Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)

    private fun openLock(root: Path): FileChannel {
        val lock = root.resolve(LOCK)
        if (Files.exists(lock, NOFOLLOW_LINKS) && Files.isSymbolicLink(lock)) {
            throw TypeScriptPublicationFailure(TypeScriptPublicationRefusal.UNSAFE_PATH)
        }
        return FileChannel.open(lock, CREATE, WRITE, NOFOLLOW_LINKS)
    }

    private fun writeFile(
        path: Path,
        content: ByteArray,
    ) {
        FileChannel.open(path, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.truncate(0)
            var bytes = ByteBuffer.wrap(content)
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    private fun readFile(
        path: Path,
        limit: Int,
    ): ByteArray? {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return null
        val size = Files.size(path)
        if (size > limit) return null
        val bytes = Files.readAllBytes(path)
        return bytes.takeIf { it.size <= limit }
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    private fun moveAtomically(
        source: Path,
        target: Path,
        replace: Boolean = false,
    ) {
        if (replace) {
            Files.move(source, target, ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } else {
            Files.move(source, target, ATOMIC_MOVE)
        }
    }

    private fun deleteStaging(path: Path) {
        if (Files.exists(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            Files.walk(path).use { files -> files.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
        }
    }

    private fun pointer(
        generation: Digest,
        declarations: ByteArray,
        manifest: ByteArray,
    ): String =
        "{\"schema\":\"rain.i18n.typescript-pointer/v1\",\"generation\":\"${generationName(
            generation,
        )}\",\"declarations\":{\"size\":${declarations.size},\"digest\":\"${Digest.sha256(
            declarations,
        ).hex}\"},\"manifest\":{\"size\":${manifest.size},\"digest\":\"${Digest.sha256(manifest).hex}\"}}\n"

    private fun parsePointer(value: String): ParsedPointer? {
        val matched = POINTER_PATTERN.matchEntire(value) ?: return null
        return ParsedPointer(
            generation = matched.groupValues[1],
            declarationsSize = matched.groupValues[2].toIntOrNull() ?: return null,
            declarationsDigest = Digest.parse(matched.groupValues[3]),
            manifestSize = matched.groupValues[4].toIntOrNull() ?: return null,
            manifestDigest = Digest.parse(matched.groupValues[5]),
        )
    }

    private fun decode(bytes: ByteArray?): String? =
        try {
            bytes?.let {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(
                        CodingErrorAction.REPORT,
                    ).decode(ByteBuffer.wrap(it))
                    .toString()
            }
        } catch (_: CharacterCodingException) {
            null
        }

    private data class ParsedPointer(
        val generation: String,
        val declarationsSize: Int,
        val declarationsDigest: Digest,
        val manifestSize: Int,
        val manifestDigest: Digest,
    )

    private companion object {
        const val GENERATIONS: String = "generations"
        const val DECLARATIONS: String = "messages.d.ts"
        const val MANIFEST: String = "messages.public.json"
        const val POINTER: String = "current.json"
        const val LOCK: String = ".rain-i18n-typescript.lock"
        const val GENERATION_PREFIX: String = "sha256-"
        const val POINTER_LIMIT_BYTES: Int = 2 * 1024
        val POINTER_PATTERN: Regex =
            Regex(
                """\A\{"schema":"rain\.i18n\.typescript-pointer/v1","generation":"(sha256-[0-9a-f]{64})","declarations":\{"size":([0-9]+),"digest":"([0-9a-f]{64})"},"manifest":\{"size":([0-9]+),"digest":"([0-9a-f]{64})"}}\n\z""",
            )

        fun generationName(digest: Digest): String = GENERATION_PREFIX + digest.hex
    }
}

private class TypeScriptPublicationFailure(
    val reason: TypeScriptPublicationRefusal,
) : IllegalStateException(reason.name)
