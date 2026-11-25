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
        "f9744307fc9594a5eb864e9985ff0c89049a38d6296dedf4fdb1de060026af72",
        "8c1f2223a334499ddaf4a7766e2f3a737d13dea150e8276f679353f6ac3f59af",
        "581f689aa61c4ba463c3a341c2e8ad190e556e9fd406126444bb1d0e3a03d7f5",
        "69040a334ba12e14ddbdfd1ce418bca0cc9c815821c3a3fc8d7cff8e461cbbbd",
        "3e321b156622dee42a32fa28375a5265343e5ccf98f01bd3736bb15f30edd8a0",
        "3a4476b0d32cfc2a67e76142cf19bd49f81995ecbb9e87037a80d69f0b98ec54",
        "d79f181eeeab44eac51ed1311f4a930b8287ff77c2102d95f379c7ef2c40e8e1",
        "bf372aa4f4a8ed51c8ad1704e09d0af6f33efcb27da29112295caabd1965bcf3",
        "c09e52f5c52c2b7d67b7c2b07ac26811953c0e87fca1511e37b9de030cac47ed",
        "7cd6103d8391f49943e123ae089e6fffdba641108b7ee145fc9aaaaeddeabb16",
        "36a6e33145f824f805ea3585c637f8082cb2fb4543dbe32076e60b6a5260b7a1",
        "7b82c523924430937ced2ce962d3858a26c006a64ed606ca20ab301fbd4c9919",
        "e3cfb6acbaca0d4d56a04cac7ff5a4c288aaa8c90e60a4c1c99c87204a736fc9",
        "eaa734a79af4f283eab33f5704730a5c4c2f37f73b432558213464384d649b51",
        "81240b78fc3f5be98b4b4729348f00b46fa4361b5f66074187be1e1ff4f0825e",
        "7022a485fd53aa97a093899684340b7fbe5c071718cf6087d1d7087dd3a0b07c",
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
