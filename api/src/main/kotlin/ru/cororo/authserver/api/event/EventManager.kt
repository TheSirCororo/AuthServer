package ru.cororo.authserver.api.event

import ru.cororo.authserver.api.plugin.Plugin

/** Marker for events. */
interface Event

/** Events whose action can be prevented by a handler. */
interface Cancellable {
    var isCancelled: Boolean
}

/** Handlers run from [LOWEST] to [MONITOR]; [MONITOR] handlers must only observe the outcome. */
enum class EventPriority { LOWEST, LOW, NORMAL, HIGH, HIGHEST, MONITOR }

fun interface EventHandler<E : Event> {
    fun handle(event: E)
}

/** Registered handler; unsubscribing is idempotent. Plugin handlers are removed when the plugin is disabled. */
interface Subscription {
    fun unsubscribe()
}

/**
 * Event bus. Handlers run synchronously on the thread that posts the event, which is never a network thread:
 * blocking work such as database access is acceptable, but slow handlers delay the player they concern.
 */
interface EventManager {
    fun <E : Event> subscribe(plugin: Plugin, type: Class<E>, priority: EventPriority, handler: EventHandler<E>): Subscription

    fun <E : Event> subscribe(plugin: Plugin, type: Class<E>, handler: EventHandler<E>): Subscription =
        subscribe(plugin, type, EventPriority.NORMAL, handler)

    /** Delivers [event] to every handler of its type and supertypes and returns it for inspection. */
    fun <E : Event> post(event: E): E
}

/** Kotlin shortcut: `server.events.on<PlayerJoinEvent>(this) { ... }`. */
inline fun <reified E : Event> EventManager.on(
    plugin: Plugin, priority: EventPriority = EventPriority.NORMAL, handler: EventHandler<E>,
): Subscription = subscribe(plugin, E::class.java, priority, handler)
