package com.yapt.planttracker.ui.screens.whatsnew

import com.yapt.planttracker.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class WhatsNewContentTest {

    @Test
    fun topEntryMatchesCurrentVersionName() {
        assertEquals(BuildConfig.VERSION_NAME, WhatsNewContent.all.first().versionName)
    }
}
