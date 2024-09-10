package util

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import logger
import kotlin.coroutines.*

val threadLocalQueueFlag = ThreadLocal<Boolean?>()

class TaskQueue(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val queue = Channel<Job>(Channel.UNLIMITED)

    init {
        scope.launch() {
            for (job in queue) {
                job.join()
            }
        }
    }

    suspend fun <T> execute(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend () -> T
    ): T {
        val flag = threadLocalQueueFlag.get()
        logger.info("是否在队列中：${flag}")
        return if (flag == true) {
            block()
        } else {
            suspendCoroutine {
                val job = scope.launch(context = context + threadLocalQueueFlag.asContextElement(true), start = CoroutineStart.LAZY) {
                    it.resume(block())
                }
                queue.trySend(job)
            }
        }
    }

    fun cancel() {
        queue.cancel()
        scope.cancel()
    }

}

val taskQueue = TaskQueue()