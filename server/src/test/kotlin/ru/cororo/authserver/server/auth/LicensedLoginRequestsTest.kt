package ru.cororo.authserver.server.auth

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LicensedLoginRequestsTest {
    private class MutableClock(var now: Instant = Instant.parse("2026-01-01T00:00:00Z")) : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
    }

    @Test
    fun `a request lets exactly one connection use mojang and then reports the failure`() {
        val requests = LicensedLoginRequests(Duration.ofMinutes(3), MutableClock())
        assertFalse(requests.consume("Buyer"))
        requests.request("Buyer")
        assertTrue(requests.consume("buyer"), "Names are case-insensitive")
        assertFalse(requests.consume("Buyer"), "Only one attempt")
        assertTrue(requests.failed("Buyer"))
        assertFalse(requests.failed("Buyer"), "Reported once")
    }

    @Test
    fun `an unused request is not a failure and requests expire`() {
        val clock = MutableClock()
        val requests = LicensedLoginRequests(Duration.ofMinutes(3), clock)
        requests.request("Buyer")
        assertFalse(requests.failed("Buyer"), "Never tried through Mojang")

        requests.request("Buyer")
        clock.now += Duration.ofMinutes(4)
        assertFalse(requests.consume("Buyer"))

        requests.request("Buyer")
        requests.consume("Buyer")
        clock.now += Duration.ofMinutes(4)
        assertFalse(requests.failed("Buyer"), "An old attempt is forgotten")
    }

    @Test
    fun `a successful login clears the request`() {
        val requests = LicensedLoginRequests(Duration.ofMinutes(3), MutableClock())
        requests.request("Buyer")
        requests.consume("Buyer")
        requests.clear("Buyer")
        assertFalse(requests.failed("Buyer"))
    }
}
