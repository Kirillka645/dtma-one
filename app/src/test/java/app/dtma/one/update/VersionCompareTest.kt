package app.dtma.one.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionCompareTest {

    @Test
    fun newerRemote() {
        assertTrue(VersionCompare.isNewer("0.2.4", "0.2.3"))
        assertTrue(VersionCompare.isNewer("v0.3.0", "0.2.9-debug"))
        assertFalse(VersionCompare.isNewer("0.2.3", "0.2.3-debug"))
        assertFalse(VersionCompare.isNewer("0.2.2", "0.2.3"))
    }

    @Test
    fun parseLatestDetectsUpdate() {
        val json = """
            {
              "tag_name": "v0.9.0",
              "html_url": "https://github.com/Kirillka645/dtma-one/releases/tag/v0.9.0",
              "body": "notes",
              "draft": false,
              "prerelease": false,
              "assets": [
                {
                  "name": "dtma-one-0.9.0-debug.apk",
                  "size": 12345,
                  "browser_download_url": "https://github.com/Kirillka645/dtma-one/releases/download/v0.9.0/dtma-one-0.9.0-debug.apk"
                }
              ]
            }
        """.trimIndent()
        val u = UpdateChecker.parseLatest(json, "0.2.3", "app.dtma.one.debug")
        assertNotNull(u)
        assertEquals("0.9.0", u!!.versionName)
        assertEquals("v0.9.0", u.tag)
        assertNotNull(u.apkUrl)
        assertTrue(u.apkUrl!!.endsWith(".apk"))
    }

    @Test
    fun parseLatestIgnoresSameOrOlder() {
        val json = """
            {"tag_name":"v0.2.3","html_url":"x","draft":false,"prerelease":false}
        """.trimIndent()
        assertNull(UpdateChecker.parseLatest(json, "0.2.3-debug"))
        assertNull(UpdateChecker.parseLatest(json, "0.2.4"))
    }

    @Test
    fun parseLatestSkipsPrerelease() {
        val json = """
            {"tag_name":"v1.0.0","html_url":"x","draft":false,"prerelease":true}
        """.trimIndent()
        assertNull(UpdateChecker.parseLatest(json, "0.1.0"))
    }
}
