package com.aicove.core.branding

import java.io.File

/**
 * 品牌标识的单一真源。
 *
 * 之前这些标识散落在各处字面量里：容器内路径 `/root/.aicode` 出现在
 * [com.aicove.feature.workspace.domain.WorkspacePathMapper]、
 * [com.aicove.core.watch.FileChangeHub]、[com.aicove.feature.terminal.domain.TerminalSessionManager]
 * 等多个文件，宿主目录名 `.aicode` 出现在 ProjectAicodeRoot 与 McpConfigRepository，
 * assets 目录 `aicode/` 与环境变量前缀 `AICODE_` 又是另外几处。
 *
 * 改包名时要同时改这些位置，漏一处就会出现「容器里找不到配置」这类难查的问题。
 * 收拢到这里后，改品牌只改这一处字面量，其余代码引用常量。
 *
 * 注意：这些是**用户可见且可迁移**的标识，改动时必须配合迁移逻辑
 * （旧数据优先、新冲突先备份、旧入口保留链接），不能直接替换了事。
 */
object BrandPaths {
    /** AiCove 配置目录在容器内的主路径。 */
    const val CONFIG_ROOT = "/root/.aicove"

    /** AiCove 配置目录在宿主/服务器上的目录名。 */
    const val CONFIG_DIR_NAME = ".aicove"

    /** 宿主 filesDir 下的 AiCove 数据目录名。 */
    const val HOST_DIR_NAME = "aicove"

    /** APK 内置资源目录；暂保留稳定路径，避免资源索引和第三方工具断裂。 */
    const val ASSETS_DIR_NAME = "aicode"

    /** 新命令向容器子进程传递的环境变量前缀。 */
    const val ENV_PREFIX = "AICOVE_"

    /**
     * 旧标识，仅供迁移读取使用。
     *
     * 迁移期必须还能认出旧路径——否则用户升级后配置目录「凭空消失」。
     * 迁移完成后这些常量可以保留一个版本，别急着删。
     */
    object Legacy {
        const val CONFIG_ROOT = "/root/.aicode"
        const val CONFIG_DIR_NAME = ".aicode"
        const val HOST_DIR_NAME = "aicode"
        const val ASSETS_DIR_NAME = "aicode"
        const val ENV_PREFIX = "AICODE_"
        const val CONFLICT_DIR_NAME = ".aicove-migration-conflicts"
    }

    const val CONFLICT_DIR_NAME = ".aicove-migration-conflicts"

    private val localRoots = ConfigRootSelection(LocalConfigMigrationFileSystem())

    fun prepareHostDirectory(filesDir: File, migrate: Boolean = true): ConfigMigrationResult {
        val old = File(filesDir, Legacy.HOST_DIR_NAME).absolutePath
        val new = File(filesDir, HOST_DIR_NAME).absolutePath
        return if (migrate) localRoots.select(old, new) else localRoots.existing(old, new)
    }

    fun hostDirectory(filesDir: File): File = File(prepareHostDirectory(filesDir).physicalRoot)

    fun prepareProjectDirectory(workspacePath: String): ConfigMigrationResult = localRoots.select(
        File(workspacePath, Legacy.CONFIG_DIR_NAME).absolutePath,
        File(workspacePath, CONFIG_DIR_NAME).absolutePath
    )

    fun projectDirectory(workspacePath: String): File = File(prepareProjectDirectory(workspacePath).physicalRoot)
}
