package ru.cororo.authserver.server.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.plugin.Plugin
import ru.cororo.authserver.api.scheduler.Scheduler
import ru.cororo.authserver.api.scheduler.Task
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/** Plugin tasks run as coroutines on the I/O dispatcher, so they may block. */
class SchedulerImpl(private val scope: CoroutineScope) : Scheduler {
    private val logger = LoggerFactory.getLogger(SchedulerImpl::class.java)
    private val tasks = ConcurrentHashMap<Plugin, MutableSet<Job>>()

    private class JobTask(private val job: Job) : Task {
        override val isCancelled: Boolean get() = job.isCancelled

        override fun cancel() = job.cancel()
    }

    override fun runAsync(plugin: Plugin, task: Runnable): Task = launch(plugin) { run(plugin, task) }

    override fun runLater(plugin: Plugin, delay: Duration, task: Runnable): Task = launch(plugin) {
        delay(delay.toMillis())
        run(plugin, task)
    }

    override fun runRepeating(plugin: Plugin, initialDelay: Duration, period: Duration, task: Runnable): Task {
        require(!period.isNegative && !period.isZero) { "Period must be positive" }
        return launch(plugin) {
            delay(initialDelay.toMillis())
            while (isActive) {
                run(plugin, task)
                delay(period.toMillis())
            }
        }
    }

    fun cancelAll(plugin: Plugin) {
        tasks.remove(plugin)?.forEach(Job::cancel)
    }

    private fun launch(plugin: Plugin, block: suspend CoroutineScope.() -> Unit): Task {
        val job = scope.launch(Dispatchers.IO, block = block)
        val jobs = tasks.computeIfAbsent(plugin) { ConcurrentHashMap.newKeySet() }
        jobs += job
        job.invokeOnCompletion { jobs -= job }
        return JobTask(job)
    }

    private fun run(plugin: Plugin, task: Runnable) {
        try {
            task.run()
        } catch (exception: Exception) {
            logger.error("Task of {} failed", plugin.description.id, exception)
        }
    }
}
