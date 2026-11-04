package com.streamflixreborn.streamflix.scrapers

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ScraperCacheTest {

    @Test
    fun `caches value until ttl expires`() = runTest {
        val cache = ScraperCache<Int>(ttlMs = 50)
        val calls = AtomicInteger()

        val first = cache.getOrFetch("key") { 10 + calls.incrementAndGet() }
        val second = cache.getOrFetch("key") { 10 + calls.incrementAndGet() }

        assertEquals(11, first)
        assertEquals(11, second)
        assertEquals(1, calls.get())

        Thread.sleep(80)

        val third = cache.getOrFetch("key") { 10 + calls.incrementAndGet() }
        assertEquals(12, third)
        assertEquals(2, calls.get())
    }

    @Test
    fun `forceRefresh bypasses cached value`() = runTest {
        val cache = ScraperCache<String>(ttlMs = 10_000)
        cache.put("k", "old")

        val refreshed = cache.getOrFetch("k", forceRefresh = true) { "new" }

        assertEquals("new", refreshed)
        assertEquals("new", cache.peek("k"))
    }

    @Test
    fun `does not cache failures`() = runTest {
        val cache = ScraperCache<Int>(ttlMs = 10_000)
        var shouldFail = true

        try {
            cache.getOrFetch("k") {
                if (shouldFail) throw IOException("boom")
                42
            }
            fail("expected failure")
        } catch (e: IOException) {
            // esperado
        }

        shouldFail = false
        assertEquals(42, cache.getOrFetch("k") { 42 })
    }

    @Test
    fun `single flight runs the block once for concurrent callers`() = runTest {
        val cache = ScraperCache<Int>(ttlMs = 10_000)
        val calls = AtomicInteger()

        val results = (1..5).map {
            async { cache.getOrFetch("shared") { calls.incrementAndGet().also { delay(20) } } }
        }.awaitAll()

        assertTrue(results.all { it == 1 })
        assertEquals(1, calls.get())
    }

    @Test
    fun `getOrFetchOrNull swallows errors and returns null`() = runTest {
        val cache = ScraperCache<Int>(ttlMs = 10_000)
        val value = cache.getOrFetchOrNull("k") { throw IOException("nope") }
        assertNull(value)
    }

    @Test
    fun `invalidate clears single entry or everything`() {
        val cache = ScraperCache<String>(ttlMs = 10_000)
        cache.put("a", "1")
        cache.put("b", "2")

        cache.invalidate("a")
        assertNull(cache.peek("a"))
        assertEquals("2", cache.peek("b"))

        cache.invalidate()
        assertNull(cache.peek("b"))
    }

    @Test
    fun `ttl zero means no expiration`() = runTest {
        val cache = ScraperCache<String>(ttlMs = 0)
        cache.put("static", "value")
        Thread.sleep(20)
        assertEquals("value", cache.peek("static"))
    }
}
