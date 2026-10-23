package com.phnem.vap.ai

import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellation requests native abort and waits for the worker's finally block before freeing its job. */
internal suspend fun <T> nativeWork(executor: Executor, cancel: () -> Unit, action: (active: () -> Boolean) -> T): T {
    val finished = CompletableDeferred<Unit>()
    try {
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { cancel() }
            try {
                executor.execute {
                    try {
                        if (continuation.isActive) {
                            val value = action { continuation.isActive }
                            if (continuation.isActive) continuation.resume(value)
                        }
                    } catch (error: Throwable) { if (continuation.isActive) continuation.resumeWithException(error) }
                    finally { finished.complete(Unit) }
                }
            } catch (error: Exception) { finished.complete(Unit); if (continuation.isActive) continuation.resumeWithException(error) }
        }
    } finally { withContext(NonCancellable) { finished.await() } }
}
