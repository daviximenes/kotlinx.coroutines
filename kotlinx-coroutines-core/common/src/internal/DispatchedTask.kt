/*
 * Copyright 2016-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.coroutines

import kotlinx.coroutines.internal.*
import kotlin.coroutines.*
import kotlin.jvm.*

/**
 * Non-cancellable dispatch mode.
 *
 * **DO NOT CHANGE THE CONSTANT VALUE**. It might be inlined into legacy user code that was calling
 * inline `suspendAtomicCancellableCoroutine` function and did not support reuse.
 */
internal const val MODE_ATOMIC = 0

/**
 * Cancellable dispatch mode. It is used by user-facing [suspendCancellableCoroutine].
 * Note, that implementation of cancellability checks mode via [Int.isCancellableMode] extension.
 *
 * **DO NOT CHANGE THE CONSTANT VALUE**. It is being into the user code from [suspendCancellableCoroutine].
 */
@PublishedApi
internal const val MODE_CANCELLABLE = 1

/**
 * Atomic dispatch mode for [suspendCancellableCoroutineReusable].
 * Note, that implementation of reuse checks mode via [Int.isReusableMode] extension.
 */
internal const val MODE_ATOMIC_REUSABLE = 2

/**
 * Cancellable dispatch mode for [suspendCancellableCoroutineReusable].
 * Note, that implementation of cancellability checks mode via [Int.isCancellableMode] extension;
 * implementation of reuse checks mode via [Int.isReusableMode] extension.
 */
internal const val MODE_CANCELLABLE_REUSABLE = 3

/**
 * Undispatched mode for [CancellableContinuation.resumeUndispatched].
 * It is used when the thread is right, but it needs to be mark it with the current coroutine.
 */
internal const val MODE_UNDISPATCHED = 4

internal val Int.isCancellableMode get() = this == MODE_CANCELLABLE || this == MODE_CANCELLABLE_REUSABLE
internal val Int.isReusableMode get() = this == MODE_ATOMIC_REUSABLE || this == MODE_CANCELLABLE_REUSABLE

internal abstract class DispatchedTask<in T>(
    @JvmField public var resumeMode: Int
) : SchedulerTask() {
    internal abstract val delegate: Continuation<T>

    internal abstract fun takeState(): Any?

    internal open fun cancelResult(state: Any?, cause: Throwable) {}

    @Suppress("UNCHECKED_CAST")
    internal open fun <T> getSuccessfulResult(state: Any?): T =
        state as T

    internal fun getExceptionalResult(state: Any?): Throwable? =
        (state as? CompletedExceptionally)?.cause

    public final override fun run() {
        val taskContext = this.taskContext
        var fatalException: Throwable? = null
        try {
            val delegate = delegate as DispatchedContinuation<T>
            val continuation = delegate.continuation
            val context = continuation.context
            val state = takeState() // NOTE: Must take state in any case, even if cancelled
            withCoroutineContext(context, delegate.countOrElement) {
                val exception = getExceptionalResult(state)
                val job = if (resumeMode.isCancellableMode) context[Job] else null
                /*
                 * Check whether continuation was originally resumed with an exception.
                 * If so, it dominates cancellation, otherwise the original exception
                 * will be silently lost.
                 */
                if (exception == null && job != null && !job.isActive) {
                    val cause = job.getCancellationException()
                    cancelResult(state, cause)
                    continuation.resumeWithStackTrace(cause)
                } else {
                    if (exception != null) continuation.resumeWithException(exception)
                    else continuation.resume(getSuccessfulResult(state))
                }
            }
        } catch (e: Throwable) {
            // This instead of runCatching to have nicer stacktrace and debug experience
            fatalException = e
        } finally {
            val result = runCatching { taskContext.afterTask() }
            handleFatalException(fatalException, result.exceptionOrNull())
        }
    }

    /**
     * Machinery that handles fatal exceptions in kotlinx.coroutines.
     * There are two kinds of fatal exceptions:
     *
     * 1) Exceptions from kotlinx.coroutines code. Such exceptions indicate that either
     *    the library or the compiler has a bug that breaks internal invariants.
     *    They usually have specific workarounds, but require careful study of the cause and should
     *    be reported to the maintainers and fixed on the library's side anyway.
     *
     * 2) Exceptions from [ThreadContextElement.updateThreadContext] and [ThreadContextElement.restoreThreadContext].
     *    While a user code can trigger such exception by providing an improper implementation of [ThreadContextElement],
     *    we can't ignore it because it may leave coroutine in the inconsistent state.
     *    If you encounter such exception, you can either disable this context element or wrap it into
     *    another context element that catches all exceptions and handles it in the application specific manner.
     *
     * Fatal exception handling can be intercepted with [CoroutineExceptionHandler] element in the context of
     * a failed coroutine, but such exceptions should be reported anyway.
     */
    internal fun handleFatalException(exception: Throwable?, finallyException: Throwable?) {
        if (exception === null && finallyException === null) return
        if (exception !== null && finallyException !== null) {
            exception.addSuppressedThrowable(finallyException)
        }

        val cause = exception ?: finallyException
        val reason = CoroutinesInternalError("Fatal exception in coroutines machinery for $this. " +
                "Please read KDoc to 'handleFatalException' method and report this incident to maintainers", cause!!)
        handleCoroutineException(this.delegate.context, reason)
    }
}

internal fun <T> DispatchedTask<T>.dispatch(mode: Int) {
    val delegate = this.delegate
    if (mode != MODE_UNDISPATCHED && delegate is DispatchedContinuation<*> && mode.isCancellableMode == resumeMode.isCancellableMode) {
        // dispatch directly using this instance's Runnable implementation
        val dispatcher = delegate.dispatcher
        val context = delegate.context
        if (dispatcher.isDispatchNeeded(context)) {
            dispatcher.dispatch(context, this)
        } else {
            resumeUnconfined()
        }
    } else {
        resume(delegate, mode)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <T> DispatchedTask<T>.resume(delegate: Continuation<T>, useMode: Int) {
    // slow-path - use delegate
    val state = takeState()
    val exception = getExceptionalResult(state)?.let { recoverStackTrace(it, delegate) }
    val result = if (exception != null) Result.failure(exception) else Result.success(state as T)
    when (useMode) {
        MODE_ATOMIC, MODE_ATOMIC_REUSABLE -> delegate.resumeWith(result)
        MODE_CANCELLABLE, MODE_CANCELLABLE_REUSABLE -> delegate.resumeCancellableWith(result)
        MODE_UNDISPATCHED -> (delegate as DispatchedContinuation).resumeUndispatchedWith(result)
        else -> error("Invalid mode $useMode")
    }
}

private fun DispatchedTask<*>.resumeUnconfined() {
    val eventLoop = ThreadLocalEventLoop.eventLoop
    if (eventLoop.isUnconfinedLoopActive) {
        // When unconfined loop is active -- dispatch continuation for execution to avoid stack overflow
        eventLoop.dispatchUnconfined(this)
    } else {
        // Was not active -- run event loop until all unconfined tasks are executed
        runUnconfinedEventLoop(eventLoop) {
            resume(delegate, MODE_UNDISPATCHED)
        }
    }
}

internal inline fun DispatchedTask<*>.runUnconfinedEventLoop(
    eventLoop: EventLoop,
    block: () -> Unit
) {
    eventLoop.incrementUseCount(unconfined = true)
    try {
        block()
        while (true) {
            // break when all unconfined continuations where executed
            if (!eventLoop.processUnconfinedEvent()) break
        }
    } catch (e: Throwable) {
        /*
         * This exception doesn't happen normally, only if we have a bug in implementation.
         * Report it as a fatal exception.
         */
        handleFatalException(e, null)
    } finally {
        eventLoop.decrementUseCount(unconfined = true)
    }
}

@Suppress("NOTHING_TO_INLINE")
internal inline fun Continuation<*>.resumeWithStackTrace(exception: Throwable) {
    resumeWith(Result.failure(recoverStackTrace(exception, this)))
}
