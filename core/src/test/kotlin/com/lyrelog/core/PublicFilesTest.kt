package com.lyrelog.core

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Nothing personal in anything public: no file the repository publishes (sources, tests,
 * README, LICENSE, build scripts, the sample) names a real person, their apps or their
 * accounts. The words are kept as SHA-256 digests, so this test never spells them.
 */
class PublicFilesTest {
    private val root = File(System.getProperty("lyrelog.repoRoot"))

    private val privateWords = setOf(
        "4859d1729b697a9ccc9bf17b6a3c676bc935834e5640d203a7cfa489bd45d619",
        "976c617a20d12e4261e40bd8e4ce88493578ec463d082af3b2784b642b82ef0d",
        "b9e5b115929357704655d70d95b79daf630c3cea6e084adec79b4bcb7e0cd7b1",
        "34eb8131c759d8ea0bdf432f943e6e78d8bd2e1d9ad50f2bf166fad074edcea8",
        "7e421181fc7b52ab66f2c22bd396acce92c3cdd9a29122dde0f1b7ddc0eb4435",
    )

    /** The digest of the first private word in [text], or null. */
    private fun namesSomeone(text: String): String? {
        val sha = MessageDigest.getInstance("SHA-256")
        for (word in text.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (word.isEmpty()) continue
            val digest = sha.digest(word.toByteArray()).joinToString("") { "%02x".format(it) }
            if (digest in privateWords) return digest
        }
        return null
    }

    /** What git would publish: tracked files plus new ones it doesn't ignore. */
    private fun publicFiles(): List<File> {
        val git = ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z")
            .directory(root).redirectErrorStream(false).start()
        val out = git.inputStream.readBytes().toString(Charsets.UTF_8)
        check(git.waitFor(30, TimeUnit.SECONDS) && git.exitValue() == 0) { "git ls-files failed" }
        return out.split('\u0000').filter { it.isNotEmpty() }.map { File(root, it) }.filter { it.isFile }
    }

    @Test
    fun theGuardSeesAPrivateWord() {
        assertEquals(null, namesSomeone("Tilefall, a made-up puzzle game by Lyrelog <hello@lyrelog.com>"))
    }

    @Test
    fun noPublicFileNamesARealPerson() {
        val files = publicFiles()
        assertTrue(files.any { it.name == "README.md" } && files.any { it.name == "LICENSE" }, "found the repository's files")
        val found = files.mapNotNull { file ->
            val bytes = file.readBytes()
            if (bytes.contains(0)) return@mapNotNull null // binary
            namesSomeone(String(bytes, Charsets.UTF_8))?.let { "${file.relativeTo(root)} (sha256 $it)" }
        }
        assertEquals(emptyList(), found, "these name someone")
    }
}
