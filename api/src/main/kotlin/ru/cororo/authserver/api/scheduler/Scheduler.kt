package ru.cororo.authserver.api.scheduler

import ru.cororo.authserver.api.plugin.Plugin
import java.time.Duration

interface Task {
    val isCancelled: Boolean

    fun cancel()
}

/** Runs plugin work off network threads. Tasks of a plugin are cancelled when it is disabled. */
interface Scheduler {
    fun runAsync(plugin: Plugin, task: Runnable): Task

    fun runLater(plugin: Plugin, delay: Duration, task: Runnable): Task

    fun runRepeating(plugin: Plugin, initialDelay: Duration, period: Duration, task: Runnable): Task
}
