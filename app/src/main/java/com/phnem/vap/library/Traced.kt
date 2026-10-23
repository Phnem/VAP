package com.phnem.vap.library

import android.os.Trace

/** Именованный участок для atrace: видно, на каком потоке и сколько занимает подготовка списков. */
internal inline fun <T> traced(name: String, block: () -> T): T {
    Trace.beginSection(name)
    try {
        return block()
    } finally {
        Trace.endSection()
    }
}
