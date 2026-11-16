package com.aicove.feature.agent.presentation

import com.aicove.core.util.FileLogger
import com.aicove.core.util.GitIgnoreMatcher
import com.aicove.feature.workspace.domain.FileEntry

/** 每个工作区/profile 独立持有；由 browse flow 串行访问，折叠目录的列表不继续缓存。 */
internal class BrowseTreeLoader(
    private val workspaceRoot: String,
    private val listFiles: (String) -> List<FileEntry>,
    private val exists: (String) -> Boolean,
    private val readFile: (String) -> String
) {
    private val listings = mutableMapOf<String, Result<List<FileEntry>>>()
    private var ignorePatterns: List<String> = emptyList()

    fun invalidate(path: String? = null) {
        if (path == null) listings.clear() else listings.remove(path)
    }

    /** 读取工作区根与当前容器根，按 [expanded] 递归展开，产出扁平的可见节点列表；工作区根读取失败则整体报错。 */
    fun build(expanded: Set<String>, containerRoot: FileTreeRoot.Container): FileBrowseState {
        listings.keys.retainAll(expanded)
        val workspaceEntries = if (workspaceRoot in expanded) {
            listDirectory(workspaceRoot).getOrElse { return FileBrowseState.Error(it.message) }
        } else {
            emptyList()
        }
        val nodes = mutableListOf<FileTreeNode>()
        nodes += rootNode(FileTreeRoot.Workspace, workspaceRoot, expanded)
        if (workspaceRoot in expanded) {
            appendBrowseChildren(
                workspaceRoot, workspaceEntries, depth = 1, expanded = expanded,
                ignorePatterns = ignorePatterns, relParts = emptyList(), out = nodes
            )
        }
        // 当前容器根：读不到（容器未安装、远程未连接）时只把该行标红，不影响工作区那棵。
        val containerExpanded = CONTAINER_FS_ROOT in expanded
        val containerEntries = if (containerExpanded) {
            listDirectory(CONTAINER_FS_ROOT).getOrNull()
        } else {
            null
        }
        nodes += rootNode(containerRoot, CONTAINER_FS_ROOT, expanded, hasError = containerExpanded && containerEntries == null)
        if (containerEntries != null) {
            appendBrowseChildren(
                CONTAINER_FS_ROOT, containerEntries, depth = 1, expanded = expanded,
                ignorePatterns = emptyList(), relParts = emptyList(), out = nodes
            )
        }
        listings.keys.retainAll(nodes.filter { it.isExpanded }.map { it.path }.toSet())
        return FileBrowseState.Success(nodes)
    }

    private fun listDirectory(path: String): Result<List<FileEntry>> = listings.getOrPut(path) {
        runCatching {
            // 按名字去重：文件系统的 readdir 在 FUSE 存储（外部工作区/模拟存储）上可能重复返回同一条目，
            // AOSP 的 ReaddirHelper 亦做同样处理。同名条目会产生重复的节点 path，撞坏 LazyColumn 的 key。
            val entries = listFiles(path).distinctBy { it.name }.sortedWith(BROWSE_ORDER)
            if (path == workspaceRoot) ignorePatterns = loadRootGitignore(path)
            entries
        }.onFailure {
            if (path == workspaceRoot) {
                FileLogger.w("AIAgentViewModel", "列目录失败: $path", it)
            }
        }
    }

    /** 文件树的一个根节点行；[FileTreeNode.entry] 的 name 同时用作显示名（见 [FileTreeRoot.label]）。 */
    private fun rootNode(root: FileTreeRoot, path: String, expanded: Set<String>, hasError: Boolean = false) = FileTreeNode(
        entry = FileEntry(name = root.label, isDirectory = true, size = 0, lastModified = 0),
        path = path,
        depth = 0,
        root = root,
        isExpanded = path in expanded,
        hasError = hasError
    )

    /** 读工作区根 .gitignore（本地/远程均可）；去空行/注释/否定行，读不到则空。仅根 .gitignore，不处理嵌套。 */
    private fun loadRootGitignore(root: String): List<String> = runCatching {
        val path = "$root/.gitignore"
        if (!exists(path)) return emptyList()
        readFile(path).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removeSuffix("/") }
            .toList()
    }.getOrDefault(emptyList())

    private fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        for (entry in entries) {
            val path = parent.trimEnd('/') + "/" + entry.name
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isExpanded = false, ignored = ignored)
                continue
            }
            val children = listDirectory(path).getOrNull()
            out += FileTreeNode(entry, path, depth, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    private companion object {
        /** 文件浏览排序：目录在前，同类按名称不区分大小写。 */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** 当前容器在容器内的根路径，对应宿主上的 rootfs 目录（随当前 profile 变化）。 */
        const val CONTAINER_FS_ROOT = "/"
    }
}
