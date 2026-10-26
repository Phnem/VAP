package com.phnem.vap.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Логика встроенного обновления без Android: разбор релиза GitHub, выбор APK, версии, подпись. */
class UpdateLogicTest {

    private val sample = """
        [
          {"tag_name":"v0.2-Stable","draft":true,"html_url":"https://github.com/Phnem/VAP/releases/tag/v0.2-Stable","assets":[]},
          {"tag_name":"v0.1-Stable","draft":false,"html_url":"https://github.com/Phnem/VAP/releases/tag/v0.1-Stable","body":"## Что нового\n- **Быстро**",
           "assets":[
             {"name":"SHA256SUMS.txt","size":286,"browser_download_url":"https://x/SHA256SUMS.txt","digest":"sha256:9be2f1af66f416d4f375ff76b8c4e6aaeb1f66cac65e69d9568449e445d89a2f"},
             {"name":"VAP-v0.1-Stable-cast.apk","size":45346028,"browser_download_url":"https://x/cast.apk","digest":"sha256:b0e4888806d042d13f1752523aab55e5ddb90e5f3ae29c73a94464da4cf9f753"},
             {"name":"VAP-v0.1-Stable-foss.apk","size":44472515,"browser_download_url":"https://x/foss.apk","digest":"sha256:a4cd1e3d7936f8abe97b2b3eac16eac398563a17a8d6add230cc9c8820fb00d8"},
             {"name":"VAP-v0.1-Stable-native-sources.zip","size":1,"browser_download_url":"https://x/src.zip"}
           ]}
        ]
    """.trimIndent()

    @Test
    fun parserSkipsDraftsAndPicksTheApkOfThisFlavor() {
        val foss = ReleaseParser.parse(sample, "foss")!!
        assertEquals("v0.1-Stable", foss.tagName)
        assertEquals("https://x/foss.apk", foss.downloadUrl)
        assertEquals(44472515L, foss.apkSize)
        assertEquals("a4cd1e3d7936f8abe97b2b3eac16eac398563a17a8d6add230cc9c8820fb00d8", foss.sha256)
        assertEquals("https://x/cast.apk", ReleaseParser.parse(sample, "cast")!!.downloadUrl)
    }

    @Test
    fun parserKeepsReleaseNotesAndSurvivesGarbage() {
        assertTrue(ReleaseParser.parse(sample, "foss")!!.body!!.contains("Быстро"))
        assertNull(ReleaseParser.parse("not json", "foss"))
        assertNull(ReleaseParser.parse("[]", "foss"))
    }

    @Test
    fun releaseWithoutApkHasNoDownloadUrl() {
        val none = ReleaseParser.parse("""[{"tag_name":"v1","draft":false,"assets":[{"name":"notes.txt","browser_download_url":"u"}]}]""", "foss")!!
        assertEquals("", none.downloadUrl)
    }

    @Test
    fun anUnnamedApkIsUsedUnlessItBelongsToAnotherFlavor() {
        assertEquals("VAP.apk", ReleaseParser.pickApk(listOf("VAP.apk" to 1), "foss")?.first)
        assertNull(ReleaseParser.pickApk(listOf("VAP-cast.apk" to 1), "foss"))
    }

    @Test
    fun digestMustBeSha256Hex() {
        assertNull(ReleaseParser.sha256Of("md5:abc"))
        assertNull(ReleaseParser.sha256Of("sha256:zz"))
        assertEquals("a".repeat(64), ReleaseParser.sha256Of("sha256:" + "A".repeat(64)))
    }

    @Test
    fun newerNumbersWinAndSuffixesDoNotMatter() {
        assertTrue(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v0.1-Stable", "v0.2-Beta"))
        assertTrue(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v0.1-Stable", "v0.1.1"))
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v0.1-Stable", "v0.1-Beta"))
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v0.2", "V0.1"))
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v0.1", "v9.0-Alpha"))
        assertFalse(AppReleaseVersionComparer.isRemoteSemanticallyNewer("v0.1", ""))
    }

    @Test
    fun signersMustExistAndMatchExactly() {
        assertTrue(ApkVerifier.sameSigners(listOf("a", "b"), listOf("b", "a")))
        assertFalse(ApkVerifier.sameSigners(emptyList(), emptyList()))
        assertFalse(ApkVerifier.sameSigners(listOf("a"), listOf("a", "b")))
        assertFalse(ApkVerifier.sameSigners(listOf("a"), listOf("c")))
    }

    @Test
    fun officialBuildNeedsEverySignerToBeOurs() {
        val ours = OfficialBuild.OFFICIAL_SHA256
        assertTrue(OfficialBuild.matches(ours.map { it.uppercase() }, ours))
        assertFalse(OfficialBuild.matches(emptyList(), ours))
        assertFalse(OfficialBuild.matches(ours + "ff".repeat(32), ours))
    }

    @Test
    fun downloadingFractionIsClamped() {
        val release = UpdateRelease("v", "", "", 100, null, null)
        assertEquals(0.5f, UpdateState.Downloading(release, 50, 100, 0).fraction, 0f)
        assertEquals(1f, UpdateState.Downloading(release, 500, 100, 0).fraction, 0f)
        assertEquals(0f, UpdateState.Downloading(release, 5, 0, 0).fraction, 0f)
    }
}
