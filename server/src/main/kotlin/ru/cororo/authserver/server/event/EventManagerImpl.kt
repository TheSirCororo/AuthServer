package ru.cororo.authserver.server.event

import org.slf4j.LoggerFactory
import ru.cororo.authserver.api.event.Event
import ru.cororo.authserver.api.event.EventHandler
import ru.cororo.authserver.api.event.EventManager
import ru.cororo.authserver.api.event.EventPriority
import ru.cororo.authserver.api.event.Subscription
import ru.cororo.authserver.api.plugin.Plugin
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class EventManagerImpl : EventManager {
    private val logger = LoggerFactory.getLogger(EventManagerImpl::class.java)
    private val subscriptions = CopyOnWriteArrayList<Registration<*>>()
    /** Handlers per concrete event class, sorted by priority; rebuilt lazily after (un)subscriptions. */
    private val cache = ConcurrentHashMap<Class<*>, List<Registration<*>>>()

    private inner class Registration<E : Event>(
        val plugin: Plugin,
        val type: Class<E>,
        val priority: EventPriority,
        val handler: EventHandler<E>,
    ) : Subscription {
        override fun unsubscribe() {
            if (subscriptions.remove(this)) cache.clear()
        }

        @Suppress("UNCHECKED_CAST")
        fun call(event: Event) = handler.handle(event as E)
    }

    override fun <E : Event> subscribe(plugin: Plugin, type: Class<E>, priority: EventPriority, handler: EventHandler<E>): Subscription =
        Registration(plugin, type, priority, handler).also {
            subscriptions += it
            cache.clear()
        }

    override fun <E : Event> post(event: E): E {
        val handlers = cache.computeIfAbsent(event.javaClass) { type ->
            subscriptions.filter { it.type.isAssignableFrom(type) }.sortedBy { it.priority }
        }
        for (registration in handlers) {
            try {
                registration.call(event)
            } catch (exception: Exception) {
                logger.error("{} failed handling {}", registration.plugin.description.id, event.javaClass.simpleName, exception)
            }
        }
        return event
    }

    /** Lets hot paths such as movement skip building events nobody listens to. */
    fun hasSubscribers(type: Class<out Event>): Boolean = subscriptions.any { it.type.isAssignableFrom(type) }

    fun unsubscribeAll(plugin: Plugin) {
        if (subscriptions.removeIf { it.plugin === plugin }) cache.clear()
    }
}
