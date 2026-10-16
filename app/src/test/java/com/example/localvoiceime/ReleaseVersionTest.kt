package com.example.localvoiceime

import org.junit.Assert.*
import org.junit.Test

class ReleaseVersionTest {
    @Test fun comparesNumericallyAndRejectsPreviewTags() {
        assertTrue(ReleaseVersion.newer("v1.10.0", "1.9.9"))
        assertTrue(ReleaseVersion.newer("v2.0.0", "1.99.99"))
        assertFalse(ReleaseVersion.newer("v1.3.5", "1.3.5"))
        assertFalse(ReleaseVersion.newer("v1.3.4", "1.3.5"))
        assertFalse(ReleaseVersion.newer("v2.0.0-beta", "1.3.5"))
        assertFalse(ReleaseVersion.newer("garbage", "1.3.5"))
        assertFalse(ReleaseVersion.newer("v99999999999999.0.0", "1.3.5"))
    }
}
