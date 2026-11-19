package com.aicove.feature.plugin.domain

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import com.aicove.feature.plugin.data.PluginPackageReader
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 拆「一个压缩包里放多个插件包」的分发形态。
 *
 * 单包：根目录直接有 plugin.json。
 * 组合包：根目录没有 plugin.json，但一级子目录里有；每个这样的子目录算一个插件包。
 *
 * 只认一级子目录，避免把插件包内部的嵌套目录误认成另一个插件。
 */
object PluginBundleReader {

    const val MANIFEST = "plugin.json"

    /** 根目录没有清单、但一级子目录里有清单：按组合包处理。 */
    fun isBundle(file: File): Boolean = runCatching {
        ZipFile(file).use { zip ->
            zip.getEntry(MANIFEST) == null && bundleDirectories(zip).isNotEmpty()
        }
    }.getOrDefault(false)

    fun bundleDirectories(file: File): List<String> =
        runCatching { ZipFile(file).use { bundleDirectories(it) } }.getOrDefault(emptyList())

    private fun bundleDirectories(zip: ZipFile): List<String> =
        zip.entries().asSequence()
            .map { it.name }
            .filter { it.endsWith("/$MANIFEST") }
            .map { it.removeSuffix("/$MANIFEST") }
            .filter { it.isNotEmpty() && '/' !in it && it != "." && it != ".." }
            .distinct()
            .sorted()
            .toList()

    /**
     * 把 [subDir] 目录下的内容重打包成独立的插件包字节流。
     *
     * 条目名去掉前缀后原样保留，越界路径由安装侧的清单/包校验负责拦截。
     */
    fun extract(file: File, subDir: String): ByteArray {
        if (subDir.isEmpty() || subDir.contains('/') || !PluginManifestParser.isSafeRelativePath(subDir)) {
            throw IOException("invalid bundle directory")
        }
        val limits = PluginPackageReader.Limits()
        if (file.length() > MAX_BUNDLE_BYTES) throw IOException("bundle archive too large")
        var total = 0L
        var count = 0
        val prefix = "$subDir/"
        val out = ByteArrayOutputStream()
        ZipFile(file).use { zip ->
            ZipOutputStream(out).use { zos ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && it.name.startsWith(prefix) }
                    .forEach { entry ->
                        val name = entry.name.removePrefix(prefix)
                        if (!PluginManifestParser.isSafeRelativePath(name)) throw IOException("unsafe bundle entry")
                        if (++count > limits.maxEntries) throw IOException("too many bundle entries")
                        zos.putNextEntry(ZipEntry(name))
                        zip.getInputStream(entry).use { input ->
                            val buffer = ByteArray(8192)
                            var entryBytes = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                entryBytes += n
                                total += n
                                if (entryBytes > limits.maxEntryBytes || total > limits.maxTotalBytes) {
                                    throw IOException("bundle contents too large")
                                }
                                zos.write(buffer, 0, n)
                            }
                        }
                        zos.closeEntry()
                    }
            }
        }
        return out.toByteArray()
    }

    private const val MAX_BUNDLE_BYTES = 64L * 1024 * 1024
}
