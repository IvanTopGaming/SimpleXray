package com.simplexray.an.core.network.http

import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response, null)
            }

            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isCancelled) return
                continuation.resumeWith(Result.failure(e))
            }
        }
    )
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Throwable) {}
    }
}
