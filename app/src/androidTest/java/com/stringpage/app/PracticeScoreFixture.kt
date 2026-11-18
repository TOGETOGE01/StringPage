package com.stringpage.app

import android.app.Instrumentation
import android.net.Uri
import java.io.File

internal fun Instrumentation.practiceScoreBytes(): ByteArray =
    context.assets.open("morning.gp").use { it.readBytes() }

internal fun Instrumentation.practiceScoreUri(): Uri = Uri.fromFile(
    File(targetContext.cacheDir, "morning-test-fixture.gp").apply { writeBytes(practiceScoreBytes()) }
)
