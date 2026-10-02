package dev.jacobhayes.bootleg.root

/**
 * A normalized absolute path split into the allowed root it lives under and the components below
 * it. Root walks the components one at a time from a directory fd of [root] (see [ConfinedFiles]),
 * so nothing here touches the filesystem.
 */
data class ConfinedPath(
    /** An allowed root, ending in `/`. */
    val root: String,
    /** At least one component; none is empty, `.` or `..`. */
    val components: List<String>,
) {
    init {
        require(root.startsWith("/") && root.endsWith("/")) { "root must be absolute and end in '/': $root" }
        require(components.isNotEmpty()) { "no components below $root" }
        require(components.none { it.isEmpty() || it == "." || it == ".." || '/' in it || '\u0000' in it }) {
            "bad component in $components"
        }
    }

    val path: String get() = root + components.joinToString("/")

    val name: String get() = components.last()

    val parentComponents: List<String> get() = components.dropLast(1)

    companion object {
        const val PATH_LENGTH_MAX = 1024
        const val COMPONENT_COUNT_MAX = 64
        const val COMPONENT_LENGTH_MAX = 255

        /** Parses [path] (already a kernel path) under the longest matching root of [roots]. */
        fun parse(
            path: String,
            roots: List<String>,
        ): ConfinedPath {
            require(roots.isNotEmpty()) { "no allowed roots" }
            require(path.length <= PATH_LENGTH_MAX) { "path too long" }
            require(path.startsWith("/") && '\u0000' !in path) { "path must be absolute: $path" }
            val root =
                roots.filter { path.startsWith(it) }.maxByOrNull { it.length }
                    ?: throw IllegalArgumentException("path is outside the allowed roots: $path")
            val components = path.removePrefix(root).split('/')
            require(components.none { it.isEmpty() || it == "." || it == ".." }) { "path is not normalized: $path" }
            require(components.size <= COMPONENT_COUNT_MAX) { "path has too many components: $path" }
            require(components.all { it.length <= COMPONENT_LENGTH_MAX }) { "path component too long: $path" }
            return ConfinedPath(root, components)
        }
    }
}
