package net.jbglenn.telltype.audio.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** Reads the `RECORD_AUDIO` grant. */
internal class AndroidMicrophonePermission @Inject constructor(@ApplicationContext private val context: Context) :
    MicrophonePermission {
    override fun isGranted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
