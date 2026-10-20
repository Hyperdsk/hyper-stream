package com.lagradost.cloudstream4.desktop.ui

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

fun providerByName(name: String): MainAPI? = APIHolder.allProviders.firstOrNull { it.name == name }

/** Runs extension code off the UI thread and turns any failure, including Errors, into a Result. */
suspend fun <T> providerCall(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }
}

fun Throwable.userMessage(): String = when (this) {
    is NotImplementedError -> "This provider doesn't support that."
    is ErrorLoadingException -> message ?: "The provider could not load this."
    is UnknownHostException -> "Couldn't reach ${message ?: "the site"}. Check your connection."
    is SocketTimeoutException -> "The site took too long to respond."
    is SSLException -> "Secure connection failed: ${message ?: ""}"
    is NoClassDefFoundError, is NoSuchMethodError -> "This extension uses Android-only code: ${message?.replace('/', '.')}"
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
