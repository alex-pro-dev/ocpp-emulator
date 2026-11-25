package com.monta.ocpp.emulator.v16.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ConnectionJobsTest {
    @Test
    fun `connect joins a cancelled job while its session cleanup is still running`() = runBlocking {
        withTimeout(5_000) {
            val cleaning = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val restarted = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val jobs = ConnectionJobs(this, {
                if (count.incrementAndGet() == 1) {
                    try {
                        currentCoroutineContext().cancel(CancellationException("Socket cancelled"))
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            cleaning.complete(Unit)
                            allowCleanup.await()
                        }
                    }
                } else {
                    restarted.complete(Unit)
                    awaitCancellation()
                }
            }, {})
            try {
                jobs.connect(1).join()
                cleaning.await()
                val replacement = jobs.connect(1)
                delay(50)
                assertFalse(restarted.isCompleted)
                allowCleanup.complete(Unit)
                replacement.join()
                restarted.await()
                assertEquals(2, count.get())
            } finally {
                allowCleanup.complete(Unit)
                jobs.disconnectAll()
            }
        }
    }

    @Test
    fun `concurrent connects share one pending handshake or backoff job`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val jobs = ConnectionJobs(this, {
                count.incrementAndGet()
                started.complete(Unit)
                awaitCancellation()
            }, {})
            try {
                coroutineScope {
                    repeat(50) { launch { jobs.connect(1).join() } }
                }
                started.await()
                jobs.connect(1).join()
                assertEquals(1, count.get())
            } finally {
                jobs.disconnectAll()
            }
        }
    }

    @Test
    fun `replacement waits for socket and child cleanup before using new settings`() = runBlocking {
        withTimeout(5_000) {
            val started = Channel<String>(Channel.UNLIMITED)
            val cleaning = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            val children = AtomicInteger()
            var url = "old-url"
            val jobs = ConnectionJobs(this, {
                val attemptUrl = url
                coroutineScope {
                    launch {
                        children.incrementAndGet()
                        try {
                            awaitCancellation()
                        } finally {
                            children.decrementAndGet()
                        }
                    }
                    started.send(attemptUrl)
                    try {
                        awaitCancellation()
                    } finally {
                        if (attemptUrl == "old-url") {
                            withContext(NonCancellable) {
                                cleaning.complete(Unit)
                                allowCleanup.await()
                            }
                        }
                    }
                }
            }, {})
            try {
                jobs.connect(1).join()
                assertEquals("old-url", started.receive())
                url = "new-url"
                val replacing = jobs.reconnect(1, 0)
                cleaning.await()
                assertFalse(replacing.isCompleted)
                assertFalse(started.tryReceive().isSuccess)
                allowCleanup.complete(Unit)
                replacing.join()
                assertEquals("new-url", started.receive())
                jobs.disconnect(1).join()
                assertEquals(0, children.get())
            } finally {
                allowCleanup.complete(Unit)
                jobs.disconnectAll()
            }
        }
    }

    @Test
    fun `disconnect cancels a scheduled reconnect and queued connect cannot resurrect after shutdown`() = runBlocking {
        withTimeout(5_000) {
            val started = CompletableDeferred<Unit>()
            val count = AtomicInteger()
            val jobs = ConnectionJobs(this, {
                count.incrementAndGet()
                started.complete(Unit)
                awaitCancellation()
            }, {})
            jobs.connect(1).join()
            started.await()
            jobs.reconnect(1, 1).join()
            jobs.connect(1).join()
            jobs.disconnect(1).join()
            delay(1_100)
            assertEquals(1, count.get())
            jobs.disconnectAll()
            jobs.connect(1).join()
            assertEquals(1, count.get())
        }
    }

    @Test
    fun `different chargers can connect independently`() = runBlocking {
        withTimeout(5_000) {
            val started = Channel<Long>(Channel.UNLIMITED)
            val jobs = ConnectionJobs(this, { id ->
                started.send(id)
                awaitCancellation()
            }, {})
            try {
                jobs.connect(1).join()
                jobs.connect(2).join()
                assertEquals(setOf(1L, 2L), setOf(started.receive(), started.receive()))
                jobs.disconnect(1).join()
                jobs.connect(2).join()
                assertFalse(started.tryReceive().isSuccess)
            } finally {
                jobs.disconnectAll()
            }
        }
    }
}
