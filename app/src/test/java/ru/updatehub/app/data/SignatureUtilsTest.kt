package ru.updatehub.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureUtilsTest {

    @Test
    fun detectsAospTestKeyInAnyFormat() {
        assertTrue(SignatureUtils.isPublicTestSignature(SignatureUtils.AOSP_TEST_KEY_SHA256))
        assertTrue(
            SignatureUtils.isPublicTestSignature(
                SignatureUtils.AOSP_TEST_KEY_SHA256.uppercase()
            )
        )
        assertTrue(
            SignatureUtils.isPublicTestSignature(
                "DF:08:C9:F2:D8:09:18:9D:9D:50:64:97:C1:57:45:A7:" +
                    "39:5A:41:53:6E:FB:43:3E:3A:EE:1A:ED:BE:11:B2:61"
            )
        )
    }

    @Test
    fun rejectsOtherAndMissingFingerprints() {
        // GitHub's official certificate is not the public test key.
        assertFalse(
            SignatureUtils.isPublicTestSignature(
                "5dff6b05761447a5bdf919ea88fc6fdf20d301e30b2315415c4d368ec0fbda45"
            )
        )
        assertFalse(SignatureUtils.isPublicTestSignature(null))
        assertFalse(SignatureUtils.isPublicTestSignature(""))
        assertFalse(SignatureUtils.isPublicTestSignature("   "))
    }
}
