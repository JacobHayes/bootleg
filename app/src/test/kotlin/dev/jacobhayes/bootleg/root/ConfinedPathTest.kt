package dev.jacobhayes.bootleg.root

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfinedPathTest {
    private val roots = listOf("/data/media/0/")

    @Test
    fun `splits a normalized path below its root`() {
        val confined = ConfinedPath.parse("/data/media/0/bootleg/a b.img", roots)
        assertEquals("/data/media/0/", confined.root)
        assertEquals(listOf("bootleg", "a b.img"), confined.components)
        assertEquals(listOf("bootleg"), confined.parentComponents)
        assertEquals("a b.img", confined.name)
        assertEquals("/data/media/0/bootleg/a b.img", confined.path)
    }

    @Test
    fun `a file directly in the root has no parent components`() {
        val confined = ConfinedPath.parse("/data/media/0/x.iso", roots)
        assertEquals(emptyList<String>(), confined.parentComponents)
    }

    @Test
    fun `the longest matching root wins`() {
        val confined = ConfinedPath.parse("/data/media/0/bootleg/x.img", listOf("/data/media/0/", "/data/media/0/bootleg/"))
        assertEquals("/data/media/0/bootleg/", confined.root)
        assertEquals(listOf("x.img"), confined.components)
    }

    @Test
    fun `paths outside the roots are rejected`() {
        assertThrows<IllegalArgumentException> { ConfinedPath.parse("/data/system/x.img", roots) }
        assertThrows<IllegalArgumentException> { ConfinedPath.parse("/data/media/01/x.img", roots) }
        assertThrows<IllegalArgumentException> { ConfinedPath.parse("/data/media/0", roots) }
        assertThrows<IllegalArgumentException> { ConfinedPath.parse("data/media/0/x.img", roots) }
    }

    @Test
    fun `unnormalized paths are rejected`() {
        for (path in listOf(
            "/data/media/0/",
            "/data/media/0//x.img",
            "/data/media/0/a/./x.img",
            "/data/media/0/a/../../x.img",
            "/data/media/0/../x.img",
            "/data/media/0/a/",
            "/data/media/0/a\u0000b.img",
        )) {
            assertThrows<IllegalArgumentException>(path) { ConfinedPath.parse(path, roots) }
        }
    }

    @Test
    fun `length and depth are bounded`() {
        val deep = "/data/media/0/" + List(ConfinedPath.COMPONENT_COUNT_MAX + 1) { "d" }.joinToString("/")
        assertThrows<IllegalArgumentException> { ConfinedPath.parse(deep, roots) }
        val longName = "/data/media/0/" + "n".repeat(ConfinedPath.COMPONENT_LENGTH_MAX + 1)
        assertThrows<IllegalArgumentException> { ConfinedPath.parse(longName, roots) }
        val longPath = "/data/media/0/" + List(200) { "abcdefgh" }.joinToString("/")
        assertThrows<IllegalArgumentException> { ConfinedPath.parse(longPath, roots) }
    }

    @Test
    fun `roots must be absolute directories`() {
        assertThrows<IllegalArgumentException> { ConfinedPath("/data/media/0", listOf("x")) }
        assertThrows<IllegalArgumentException> { ConfinedPath("/data/media/0/", emptyList()) }
        assertThrows<IllegalArgumentException> { ConfinedPath("/data/media/0/", listOf("a/b")) }
    }
}
