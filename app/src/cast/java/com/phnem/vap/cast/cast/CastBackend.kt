package com.phnem.vap.cast.cast

import android.content.Context
import com.phnem.vap.cast.RemotePlaybackAdapter

/** Сборка с Google Cast (GitHub-релизы). F-Droid удаляет папку src/cast — там берётся src/nocast. */
fun createCastAdapter(context: Context): RemotePlaybackAdapter? =
    GoogleCastAdapter(context).takeIf { it.available }
