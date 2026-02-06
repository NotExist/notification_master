package com.notificationmaster

import com.notificationmaster.core.dedup.ContentHashGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * ContentHashGenerator 單元測試
 */
class ContentHashGeneratorTest {

    @Test
    fun `generateHash produces consistent hash for same input`() {
        val hash1 = ContentHashGenerator.generateHash(
            packageName = "com.example.app",
            title = "Test Title",
            text = "Test Text",
            bigText = null
        )

        val hash2 = ContentHashGenerator.generateHash(
            packageName = "com.example.app",
            title = "Test Title",
            text = "Test Text",
            bigText = null
        )

        assertEquals(hash1, hash2)
    }

    @Test
    fun `generateHash produces different hash for different package`() {
        val hash1 = ContentHashGenerator.generateHash(
            packageName = "com.example.app1",
            title = "Test Title",
            text = "Test Text",
            bigText = null
        )

        val hash2 = ContentHashGenerator.generateHash(
            packageName = "com.example.app2",
            title = "Test Title",
            text = "Test Text",
            bigText = null
        )

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `generateHash produces different hash for different title`() {
        val hash1 = ContentHashGenerator.generateHash(
            packageName = "com.example.app",
            title = "Title 1",
            text = "Test Text",
            bigText = null
        )

        val hash2 = ContentHashGenerator.generateHash(
            packageName = "com.example.app",
            title = "Title 2",
            text = "Test Text",
            bigText = null
        )

        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `generateHash handles null values correctly`() {
        val hash1 = ContentHashGenerator.generateHash(
            packageName = "com.example.app",
            title = null,
            text = null,
            bigText = null
        )

        val hash2 = ContentHashGenerator.generateHash(
            packageName = "com.example.app",
            title = null,
            text = null,
            bigText = null
        )

        assertEquals(hash1, hash2)
    }

    @Test
    fun `generateHash produces 64 character SHA-256 hash`() {
        val hash = ContentHashGenerator.generateHash(
            packageName = "com.example.app",
            title = "Title",
            text = "Text",
            bigText = "BigText"
        )

        // SHA-256 產生 64 個十六進位字元
        assertEquals(64, hash.length)
    }

    @Test
    fun `generateFullHash includes progress in hash`() {
        val hash1 = ContentHashGenerator.generateFullHash(
            packageName = "com.example.app",
            title = "Downloading",
            text = "50%",
            bigText = null,
            subText = null,
            progress = 50,
            progressMax = 100
        )

        val hash2 = ContentHashGenerator.generateFullHash(
            packageName = "com.example.app",
            title = "Downloading",
            text = "50%",
            bigText = null,
            subText = null,
            progress = 75,
            progressMax = 100
        )

        assertNotEquals(hash1, hash2)
    }
}
