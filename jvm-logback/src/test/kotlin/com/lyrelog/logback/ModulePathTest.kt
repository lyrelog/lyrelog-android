package com.lyrelog.logback

import java.io.File
import java.lang.module.ModuleFinder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The published jars on the Java module path: each has a stable name (Automatic-Module-Name),
 * and no two export the same package (a split package makes the JVM refuse to start). This
 * failed while `com.lyrelog` was in both lyrelog-core and lyrelog-jvm.
 */
class ModulePathTest {
    private val jars = System.getProperty("lyrelog.modulePath").split(File.pathSeparator).map(::File)

    @Test
    fun eachJarHasItsModuleName() {
        val names = ModuleFinder.of(*jars.map { it.toPath() }.toTypedArray()).findAll().map { it.descriptor().name() }.toSet()
        assertTrue(names.containsAll(setOf("com.lyrelog.core", "com.lyrelog.jvm", "com.lyrelog.logback")), names.toString())
    }

    @Test
    fun theJarsLoadAsModulesWithoutASplitPackage() {
        val finder = ModuleFinder.of(*jars.map { it.toPath() }.toTypedArray())
        val boot = ModuleLayer.boot()
        val configuration = boot.configuration().resolve(finder, ModuleFinder.of(), setOf("com.lyrelog.logback", "com.lyrelog.jvm", "com.lyrelog.core"))
        val layer = boot.defineModulesWithOneLoader(configuration, ClassLoader.getPlatformClassLoader())
        val lyrelog = layer.findLoader("com.lyrelog.jvm").loadClass("com.lyrelog.jvm.Lyrelog")
        assertEquals("com.lyrelog.jvm", lyrelog.module.name)
        assertEquals("com.lyrelog.core", layer.findLoader("com.lyrelog.core").loadClass("com.lyrelog.Level").module.name)
        val packages = layer.modules().filter { it.name.startsWith("com.lyrelog") }.associate { it.name to it.packages }
        assertEquals(setOf("com.lyrelog", "com.lyrelog.core"), packages["com.lyrelog.core"])
        assertEquals(setOf("com.lyrelog.jvm"), packages["com.lyrelog.jvm"])
        assertEquals(setOf("com.lyrelog.logback"), packages["com.lyrelog.logback"])
    }
}
