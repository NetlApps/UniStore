package ru.updatehub.app.data.checkers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkPureApiTest {

    private val sha1 = "38a0f7d505fe18fec64fbf343ecaaaf310dbd799"

    private fun body(
        versions: String = """
            {
              "retcode": 0,
              "errmsg": "",
              "version_list": [
                {
                  "title": "WhatsApp",
                  "version_name": "2.26.42.74",
                  "version_code": "264207400",
                  "sign": ["$sha1"],
                  "native_code": ["arm64-v8a"],
                  "sdk_version": "23",
                  "is_a_p_ks": true,
                  "asset": {"type": "XAPK", "url": "https://data.winudf.com/arm64"}
                },
                {
                  "title": "WhatsApp",
                  "version_name": "2.26.42.74",
                  "version_code": "264207400",
                  "sign": ["$sha1"],
                  "native_code": ["armeabi-v7a"],
                  "sdk_version": "24",
                  "is_a_p_ks": true,
                  "asset": {"type": "XAPK", "url": "https://data.winudf.com/arm32"}
                },
                {
                  "title": "WhatsApp",
                  "version_name": "2.26.41.77",
                  "version_code": "264107700",
                  "native_code": ["arm64-v8a"],
                  "sdk_version": "23",
                  "asset": {"type": "XAPK", "url": "https://data.winudf.com/old"}
                }
              ]
            }
        """.trimIndent()
    ): String = versions

    @Test
    fun `picks arm64 variant and newest version`() {
        val result = ApkPureApi.parse(body(), listOf("arm64-v8a", "armeabi-v7a"))
        assertTrue(result is ApkPureApiResult.Found)
        val release = (result as ApkPureApiResult.Found).release

        assertEquals("2.26.42.74", release.versionName)
        assertEquals(264207400L, release.versionCode)
        assertEquals("https://data.winudf.com/arm64", release.downloadUrl)
        assertEquals(sha1, release.signatureSha1)
        assertEquals("WhatsApp", release.title)
        // Metadata is merged across every variant of the same version, so
        // compatibility does not depend on which asset was picked.
        assertEquals(setOf("arm64-v8a", "armeabi-v7a"), release.details.architectures)
        assertEquals(23, release.details.minSdk)
        assertEquals(true, release.details.isBundle)
    }

    @Test
    fun `picks 32 bit asset on a 32 bit phone`() {
        val result = ApkPureApi.parse(body(), listOf("armeabi-v7a"))
        val release = (result as ApkPureApiResult.Found).release

        assertEquals("https://data.winudf.com/arm32", release.downloadUrl)
    }

    @Test
    fun `universal variant removes architecture restriction`() {
        val json = """
            {
              "retcode": 0,
              "version_list": [{
                "version_name": "9.1.94.1929",
                "version_code": "147600575",
                "native_code": ["armeabi-v7a"],
                "sdk_version": "24",
                "asset": {"type": "XAPK", "url": "https://data.winudf.com/xapk"}
              }, {
                "version_name": "9.1.94.1929",
                "version_code": "147600575",
                "native_code": [],
                "sdk_version": "24",
                "asset": {"type": "APK", "url": "https://data.winudf.com/universal"}
              }]
            }
        """.trimIndent()

        val release = (ApkPureApi.parse(json, listOf("arm64-v8a")) as ApkPureApiResult.Found)
            .release

        assertEquals("https://data.winudf.com/universal", release.downloadUrl)
        assertEquals(emptySet<String>(), release.details.architectures)
        assertEquals(false, release.details.isBundle)
    }

    @Test
    fun `chrome os build does not win over the phone release`() {
        val json = """
            {
              "retcode": 0,
              "version_list": [{
                "version_name": "2000000000.0.0-CrOS",
                "version_code": "2000000000",
                "native_code": [],
                "asset": {"type": "APK", "url": "https://data.winudf.com/cros"}
              }, {
                "version_name": "14.25.0",
                "version_code": "14258020",
                "native_code": ["arm64-v8a"],
                "asset": {"type": "XAPK", "url": "https://data.winudf.com/phone"}
              }]
            }
        """.trimIndent()

        val release = (ApkPureApi.parse(json, listOf("arm64-v8a")) as ApkPureApiResult.Found)
            .release

        assertEquals("14.25.0", release.versionName)
        assertEquals(14258020L, release.versionCode)
        assertEquals("https://data.winudf.com/phone", release.downloadUrl)
    }

    @Test
    fun `only chrome os builds means not found`() {
        val json = """
            {
              "retcode": 0,
              "version_list": [{
                "version_name": "2000000000.0.0-CrOS",
                "version_code": "2000000000",
                "native_code": [],
                "asset": {"type": "APK", "url": "https://data.winudf.com/cros"}
              }]
            }
        """.trimIndent()

        assertEquals(ApkPureApiResult.Missing, ApkPureApi.parse(json, listOf("arm64-v8a")))
    }

    @Test
    fun `empty version list means not found`() {
        assertEquals(
            ApkPureApiResult.Missing,
            ApkPureApi.parse("""{"retcode":0,"errmsg":"success","version_list":[]}""")
        )
    }

    @Test
    fun `non zero retcode is a failure`() {
        val result = ApkPureApi.parse(
            """{"retcode":403,"errmsg":"access denied","version_list":[]}"""
        )

        assertTrue(result is ApkPureApiResult.Failed)
        assertTrue((result as ApkPureApiResult.Failed).message.contains("access denied"))
    }

    @Test
    fun `invalid json is a failure`() {
        val result = ApkPureApi.parse("<html>Cloudflare</html>")

        assertTrue(result is ApkPureApiResult.Failed)
    }

    @Test
    fun `sha256 signature is stored separately`() {
        val hash = "a".repeat(64)
        val json = """
            {
              "retcode": 0,
              "version_list": [{
                "version_name": "1.0",
                "version_code": "1",
                "sign": ["$hash"],
                "native_code": ["universal"],
                "asset": {"type": "APK", "url": "https://example.test/app.apk"}
              }]
            }
        """.trimIndent()

        val release = (ApkPureApi.parse(json) as ApkPureApiResult.Found).release

        assertEquals(hash, release.signature)
        assertNull(release.signatureSha1)
    }

    @Test
    fun `detail endpoint gives version, code and signature without abi data`() {
        val json = """
            {
              "retcode": 0,
              "errmsg": "",
              "app_detail": {
                "title": "WhatsApp",
                "version_name": "2.26.42.74",
                "version_code": "264207400",
                "sign": "$sha1"
              }
            }
        """.trimIndent()

        val result = ApkPureApi.parseDetail(json)

        assertTrue(result is ApkPureApiResult.Found)
        val release = (result as ApkPureApiResult.Found).release
        assertEquals("2.26.42.74", release.versionName)
        assertEquals(264207400L, release.versionCode)
        assertEquals(sha1, release.signatureSha1)
        assertEquals("WhatsApp", release.title)
        assertTrue(release.fromDetailOnly)
        assertTrue(release.details.architectures.isEmpty())
    }

    @Test
    fun `detail endpoint rejects chromeos build for a phone app`() {
        // The real APKPure answer for Pinterest is 2000000000.0.0-CrOS while
        // the phone release is 14.x. The compact route must fall through to
        // the history list instead of turning this into an update.
        val json = """
            {
              "retcode": 0,
              "app_detail": {
                "title": "Pinterest",
                "version_name": "2000000000.0.0-CrOS",
                "version_code": "2000000000",
                "sign": "b6a74dbcb894b0f73d8c485c72eb1247a8f027ca"
              }
            }
        """.trimIndent()

        assertEquals(ApkPureApiResult.Missing, ApkPureApi.parseDetail(json))
    }

    @Test
    fun `api url and headers match the public endpoint`() {
        val url = ApkPureApi.url("com.whatsapp")
        assertTrue(url.startsWith("https://tapi.pureapk.com/v3/get_app_his_version?"))
        assertTrue(url.contains("package_name=com.whatsapp"))
        assertTrue(url.contains("hl=en"))

        val headers = ApkPureApi.headers(35)
        assertEquals("projecta", headers["Ual-Access-Businessid"])
        assertTrue(headers.getValue("Ual-Access-ProjectA").contains("\"os_ver\":\"35\""))
    }
}
