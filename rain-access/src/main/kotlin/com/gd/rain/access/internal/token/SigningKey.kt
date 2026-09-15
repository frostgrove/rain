package com.gd.rain.access.internal.token

import com.gd.rain.boot.runtime.DeploymentStage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Base64

/** The key material a written signing key resolves to, or why it does not resolve. Never carries the written value. */
public sealed interface SigningKeyResolution {
    public class Resolved(
        public val material: ByteArray,
    ) : SigningKeyResolution

    public data class Refused(
        public val problem: String,
    ) : SigningKeyResolution
}

/**
 * `rain.access.token.signing-key`, in one of three written forms:
 *
 * - `base64:<key>` — the key in strict RFC 4648 base64: the standard alphabet, padded to a multiple of four, no
 *   whitespace, and canonical (the padding bits are zero), so one key has exactly one spelling;
 * - `file:<absolute path>` — a file holding the key in that same strict base64, optionally followed by one line feed;
 * - anything else — a raw literal whose UTF-8 bytes are the key; refused in the `prod` stage, where a key written into a
 *   file is a key in a repository.
 *
 * The material is at least [MIN_BYTES] bytes, HS256's key size. Nothing judges how random it looks: a length is a rule,
 * an entropy estimate is a guess.
 */
public object SigningKey {
    public const val MIN_BYTES: Int = 32
    public const val BASE64_PREFIX: String = "base64:"
    public const val FILE_PREFIX: String = "file:"

    private val STRICT_BASE64 = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
    private const val LINE_FEED = '\n'

    public fun resolve(
        written: String,
        stage: DeploymentStage,
        read: (Path) -> ByteArray = Files::readAllBytes,
    ): SigningKeyResolution {
        val material =
            when {
                written.startsWith(BASE64_PREFIX) -> {
                    decodeStrict(written.removePrefix(BASE64_PREFIX))
                        ?: return SigningKeyResolution.Refused(
                            "is written as $BASE64_PREFIX but is not strict RFC 4648 base64 (standard alphabet, padded, " +
                                "no whitespace); generate one with `openssl rand -base64 32`",
                        )
                }

                written.startsWith(FILE_PREFIX) -> {
                    when (val file = readFile(written.removePrefix(FILE_PREFIX), read)) {
                        is FileRead.Failed -> return SigningKeyResolution.Refused(file.problem)
                        is FileRead.Content -> file.material
                    }
                }

                stage == DeploymentStage.PROD -> {
                    return SigningKeyResolution.Refused(
                        "is a raw literal in the ${stage.wire} stage; state $BASE64_PREFIX<key> or $FILE_PREFIX<absolute path>",
                    )
                }

                else -> {
                    written.toByteArray(Charsets.UTF_8)
                }
            }
        if (material.size < MIN_BYTES) {
            return SigningKeyResolution.Refused("holds ${material.size} bytes of key material; HS256 needs at least $MIN_BYTES")
        }
        return SigningKeyResolution.Resolved(material)
    }

    /** The bytes of [text] when it is canonical strict base64, otherwise `null`. */
    public fun decodeStrict(text: String): ByteArray? {
        if (text.isEmpty() || !STRICT_BASE64.matches(text)) return null
        val decoded = Base64.getDecoder().decode(text)
        return decoded.takeIf { Base64.getEncoder().encodeToString(it) == text }
    }

    private sealed interface FileRead {
        class Content(
            val material: ByteArray,
        ) : FileRead

        data class Failed(
            val problem: String,
        ) : FileRead
    }

    private fun readFile(
        written: String,
        read: (Path) -> ByteArray,
    ): FileRead {
        val path =
            try {
                Paths.get(written)
            } catch (malformed: java.nio.file.InvalidPathException) {
                return FileRead.Failed("names a key file whose path cannot be read: ${malformed.reason}")
            }
        if (!path.isAbsolute) return FileRead.Failed("names a key file by a relative path; state an absolute one")
        val bytes =
            try {
                read(path)
            } catch (unreadable: IOException) {
                return FileRead.Failed("names a key file that cannot be read: ${unreadable.javaClass.simpleName}")
            }
        val text = String(bytes, Charsets.US_ASCII)
        val body = if (text.endsWith(LINE_FEED)) text.dropLast(1) else text
        val material =
            decodeStrict(body)
                ?: return FileRead.Failed(
                    "names a key file that does not hold strict RFC 4648 base64 followed by at most one line feed",
                )
        return FileRead.Content(material)
    }
}
