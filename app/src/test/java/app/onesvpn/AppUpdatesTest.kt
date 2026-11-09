package app.onesvpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun publicReleaseRepositoryIsUsedForNewStableUpdates() {
        assertEquals(
            "https://api.github.com/repos/salfetka1222/ones-vpn-public/releases",
            AppUpdates.API
        )
        assertEquals(
            "https://github.com/salfetka1222/ones-vpn-public/releases/download/",
            AppUpdates.ASSET_PREFIX
        )
    }

    private fun release(version: Int) = JSONObject().put("tag_name", "ones-vpn-alpha-$version")
        .put("assets", JSONArray().put(asset(version, "ONES-VPN-alpha.apk")).put(asset(version, "SHA256SUMS.txt")))
    private fun asset(version: Int, name: String) = JSONObject().put("name", name)
        .put("browser_download_url", "https://github.com/salfetka1222/ones-vpn-public/releases/download/ones-vpn-alpha-$version/$name")

    private fun stableRelease(version: Int, prerelease: Boolean = false): JSONObject {
        val tag = "ones-vpn-stable-" + version
        val assets = JSONArray()
        for (name in listOf("ONES-VPN.apk", "SHA256SUMS.txt")) {
            assets.put(JSONObject().put("name", name).put("browser_download_url",
                "https://github.com/salfetka1222/ones-vpn-public/releases/download/" + tag + "/" + name))
        }
        return JSONObject().put("tag_name", tag).put("prerelease", prerelease)
            .put("assets", assets)
    }

    @Test fun stableReleaseIsChosenAndAssetMatchesChecksumFilename() {
        val choice = AppUpdates.parseReleases(
            JSONArray().put(release(32)).put(stableRelease(34)), 33
        )
        assertNotNull(choice)
        assertEquals(34, choice!!.version)
        assertEquals("ONES-VPN.apk", choice.assetName)
        assertTrue(choice.apk.endsWith("/ONES-VPN.apk"))
        assertTrue(choice.checksum.endsWith("/SHA256SUMS.txt"))
    }

    @Test fun stableChannelWinsTieWithoutDowngrading() {
        val choice = AppUpdates.parseReleases(
            JSONArray().put(release(34)).put(stableRelease(34)), 33
        )
        assertEquals("ONES-VPN.apk", choice?.assetName)
        assertNull(AppUpdates.parseReleases(JSONArray().put(stableRelease(34)), 34))
        assertNull(AppUpdates.parseReleases(JSONArray().put(stableRelease(34)), 35))
    }

    @Test fun releaseCandidateCannotPretendToBeStable() {
        val unpublished = stableRelease(34, prerelease = true)
        val unknownTag = stableRelease(35).put("tag_name", "ones-vpn-rc-35")
        assertNull(AppUpdates.parseReleases(
            JSONArray().put(unpublished).put(unknownTag), 33
        ))
    }

    @Test fun rejectsStableWithWrongAssetNameOrForeignDownloadLink() {
        val wrongAsset = stableRelease(34)
        val assets = wrongAsset.getJSONArray("assets")
        assets.getJSONObject(0).put("name", "ONES-VPN-alpha.apk")
        val wrongUrl = stableRelease(35)
        wrongUrl.getJSONArray("assets").getJSONObject(0)
            .put("browser_download_url", "https://evil.example.net/ONES-VPN.apk")
        assertNull(AppUpdates.parseReleases(
            JSONArray().put(wrongAsset).put(wrongUrl), 33
        ))
    }

    @Test fun stableChecksumIsParsedOnlyForExactSignedAsset() {
        val hash = "a".repeat(64)
        val line = hash + "  ONES-VPN.apk\r\n"
        assertEquals(hash, AppUpdates.expectedSha256(line, "ONES-VPN.apk"))
        assertNull(AppUpdates.expectedSha256(line, "ONES-VPN-alpha.apk"))
        assertNull(AppUpdates.expectedSha256(line, "../ONES-VPN.apk"))
    }

    @Test fun duplicateChecksumsAndWrongFilenameFailClosed() {
        val hash = "b".repeat(64)
        val line = hash + " *ONES-VPN.apk"
        assertNull(AppUpdates.expectedSha256(line + "\n" + line, "ONES-VPN.apk"))
        assertNull(AppUpdates.expectedSha256(hash + "  ONES-VPN.apk.sig", "ONES-VPN.apk"))
        assertNull(AppUpdates.expectedSha256("not-a-hash  ONES-VPN.apk", "ONES-VPN.apk"))
    }

    @Test fun selectsHighestVersionRatherThanFirstRelease() {
        assertEquals(9, AppUpdates.parseReleases(JSONArray().put(release(8)).put(release(9)), 7)?.version)
    }
    @Test fun ignoresDowngradesDraftsAndForeignAssets() {
        val draft = release(10).put("draft", true)
        val foreign = release(11).put("assets", JSONArray().put(JSONObject().put("name", "ONES-VPN-alpha.apk").put("browser_download_url", "https://evil.example/app.apk")))
        assertNull(AppUpdates.parseReleases(JSONArray().put(release(6)).put(draft).put(foreign), 7))
    }

    @Test fun installedStableDoesNotOfferHigherNumberedLegacyAlpha() {
        val releases = JSONArray().put(release(90)).put(stableRelease(37))
        val choice = AppUpdates.parseReleases(releases, 36, allowLegacyAlpha = false)
        assertEquals(37, choice?.version)
        assertEquals("ONES-VPN.apk", choice?.assetName)
        assertNull(AppUpdates.parseReleases(JSONArray().put(release(90)), 36,
            allowLegacyAlpha = false))
    }

    @Test fun releaseCandidateCanStillMigrateThroughLegacyAlpha() {
        val choice = AppUpdates.parseReleases(JSONArray().put(release(36)), 35,
            allowLegacyAlpha = true)
        assertEquals(36, choice?.version)
        assertEquals("ONES-VPN-alpha.apk", choice?.assetName)
    }

    @Test fun olderStableDoesNotDowngradeToLowerStable() {
        assertNull(AppUpdates.parseReleases(JSONArray().put(stableRelease(36)), 37,
            allowLegacyAlpha = false))
    }
}
