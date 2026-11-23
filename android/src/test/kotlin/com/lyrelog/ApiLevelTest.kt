package com.lyrelog

import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.parsers.SAXParserFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * `core` is a plain JVM jar, so lint never checks it against the app's minSdk: every Java class,
 * method and field it calls must exist on Android 24. Read from its compiled classes and checked
 * against the SDK's `api-versions.xml` (what lint itself uses). A call that isn't there is a
 * `NoClassDefFoundError` or `NoSuchMethodError` on an older phone: an Error, on Lyrelog's own
 * thread, which ends the app.
 */
class ApiLevelTest {
    private val minSdk = 24

    @Test
    fun coreUsesOnlyWhatAndroid24Has() {
        val api = AndroidApi.load(apiVersionsFile())
        val problems = sortedSetOf<String>()
        for ((name, bytes) in coreClasses()) {
            for (ref in ClassRefs.read(bytes)) {
                val owner = ref.owner.removePrefix("[").trimStart('[').removePrefix("L").removeSuffix(";")
                if (!api.covers(owner)) continue
                val since = if (ref.member == null) api.classSince(owner) else api.memberSince(owner, ref.member)
                if (since == null) {
                    problems += "$name: ${ref.owner}${ref.member?.let { "#$it" } ?: ""} is not on Android"
                } else if (since > minSdk) {
                    problems += "$name: ${ref.owner}${ref.member?.let { "#$it" } ?: ""} needs API $since"
                }
            }
        }
        assertTrue("core calls what Android $minSdk doesn't have:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private fun coreClasses(): List<Pair<String, ByteArray>> {
        val location = File(com.lyrelog.core.Engine::class.java.protectionDomain!!.codeSource.location.toURI())
        val out = ArrayList<Pair<String, ByteArray>>()
        if (location.isDirectory) {
            location.walkTopDown().filter { it.name.endsWith(".class") }.forEach { out += it.name to it.readBytes() }
        } else {
            ZipFile(location).use { zip ->
                for (entry in zip.entries()) {
                    if (entry.name.endsWith(".class")) out += entry.name to zip.getInputStream(entry).use(InputStream::readBytes)
                }
            }
        }
        assertTrue("no classes found in $location", out.any { it.first.contains("Engine") })
        return out
    }

    private fun apiVersionsFile(): File {
        val local = File("../local.properties").takeIf { it.isFile }?.readLines()
            ?.firstOrNull { it.startsWith("sdk.dir=") }?.substringAfter("=")
        val sdk = listOfNotNull(local, System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .map(::File).firstOrNull { it.isDirectory } ?: error("no Android SDK")
        return (sdk.resolve("platforms").listFiles() ?: emptyArray())
            .map { it.resolve("data/api-versions.xml") }.filter { it.isFile }
            .maxByOrNull { it.parentFile?.parentFile?.name.orEmpty() } ?: error("no api-versions.xml in $sdk")
    }
}

/** A class's or member's first API level, from `api-versions.xml`. */
private class AndroidApi(
    private val classes: Map<String, Int>,
    private val members: Map<String, Map<String, Int>>,
    private val parents: Map<String, List<String>>,
) {
    /**
     * Only the platform's own packages are checked (Kotlin's stdlib ships with the app), less
     * the bootstraps of lambdas and string concatenation, which D8 always rewrites.
     */
    fun covers(owner: String) =
        (owner.startsWith("java/") || owner.startsWith("javax/") || owner.startsWith("android/")) && owner !in desugared

    private val desugared = setOf("java/lang/invoke/LambdaMetafactory", "java/lang/invoke/StringConcatFactory")

    fun classSince(owner: String): Int? = classes[owner]

    /** The member on the class or one of its parents; a method without its own `since` is the class's. */
    fun memberSince(owner: String, member: String): Int? {
        val seen = HashSet<String>()
        val queue = ArrayDeque(listOf(owner))
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (!seen.add(current)) continue
            members[current]?.get(member)?.let { return maxOf(it, classes[owner] ?: it) }
            queue.addAll(parents[current].orEmpty())
        }
        return null
    }

    companion object {
        fun load(file: File): AndroidApi {
            val classes = HashMap<String, Int>()
            val members = HashMap<String, HashMap<String, Int>>()
            val parents = HashMap<String, MutableList<String>>()
            var current: String? = null
            var classSince = 1
            SAXParserFactory.newInstance().newSAXParser().parse(file, object : DefaultHandler() {
                override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
                    val name = attributes.getValue("name") ?: return
                    val since = attributes.getValue("since")?.toIntOrNull()
                    val removed = attributes.getValue("removed")?.toIntOrNull()
                    when (qName) {
                        "class" -> {
                            current = name
                            classSince = since ?: 1
                            if (removed == null || removed > 24) classes[name] = classSince
                        }
                        "extends", "implements" -> current?.let { parents.getOrPut(it) { ArrayList() }.add(name) }
                        "method", "field" -> current?.let {
                            if (removed == null || removed > 24) members.getOrPut(it) { HashMap() }[name] = since ?: classSince
                        }
                    }
                }

                override fun endElement(uri: String?, localName: String?, qName: String) {
                    if (qName == "class") current = null
                }
            })
            return AndroidApi(classes, members, parents)
        }
    }
}

/** The classes, methods and fields a class file refers to, from its constant pool. */
private object ClassRefs {
    class Ref(val owner: String, val member: String?)

    fun read(bytes: ByteArray): List<Ref> {
        val input = DataInputStream(bytes.inputStream())
        check(input.readInt() == 0xCAFEBABE.toInt())
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        val classNames = HashMap<Int, Int>()
        val nameAndTypes = HashMap<Int, Pair<Int, Int>>()
        val memberRefs = ArrayList<Triple<Int, Int, Boolean>>() // class index, name-and-type index, is field
        var i = 1
        while (i < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[i] = input.readUTF()
                3, 4 -> input.readInt()
                5, 6 -> {
                    input.readLong()
                    i++
                }
                7 -> classNames[i] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.readUnsignedShort()
                9, 10, 11 -> memberRefs += Triple(input.readUnsignedShort(), input.readUnsignedShort(), tag == 9)
                12 -> nameAndTypes[i] = input.readUnsignedShort() to input.readUnsignedShort()
                15 -> {
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                }
                17, 18 -> {
                    input.readUnsignedShort()
                    input.readUnsignedShort()
                }
                else -> error("constant pool tag $tag")
            }
            i++
        }
        val out = ArrayList<Ref>()
        for (nameIndex in classNames.values) out += Ref(utf8.getValue(nameIndex), null)
        for ((classIndex, natIndex, field) in memberRefs) {
            val owner = utf8.getValue(classNames.getValue(classIndex))
            val (name, descriptor) = nameAndTypes.getValue(natIndex)
            // api-versions.xml writes a field by its name alone, a method with its descriptor.
            out += Ref(owner, if (field) utf8.getValue(name) else utf8.getValue(name) + utf8.getValue(descriptor))
        }
        return out
    }
}
