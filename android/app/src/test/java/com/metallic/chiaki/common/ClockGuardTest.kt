package com.metallic.chiaki.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.util.TimeZone
import javax.net.ssl.SSLHandshakeException

class ClockGuardTest {

    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val london = TimeZone.getTimeZone("Europe/London")
    private val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
    private val la = TimeZone.getTimeZone("America/Los_Angeles")

    // 2026-10-01 12:00 UTC
    private val noonUtc = 1790856000000L

    @Test
    fun `date moved forward a day is caught in any country`() {
        for (tz in listOf(london, tokyo, la, TimeZone.getTimeZone("UTC")))
            assertTrue(tz.id, ClockGuard.isDeviceDateWrong(noonUtc + day, noonUtc, tz))
    }

    @Test
    fun `date moved back a day is caught`() {
        assertTrue(ClockGuard.isDeviceDateWrong(noonUtc - day, noonUtc, london))
    }

    @Test
    fun `correct clock is fine`() {
        assertFalse(ClockGuard.isDeviceDateWrong(noonUtc, noonUtc, london))
        assertFalse(ClockGuard.isDeviceDateWrong(noonUtc + 30_000, noonUtc, tokyo))
    }

    @Test
    fun `small drift across midnight doesn't count`() {
        // 23:59:30 vs 00:00:30 London time: different dates, but only a minute apart.
        val londonMidnight = 1790895600000L // 2026-10-01 23:00 UTC = 2026-10-02 00:00 BST
        assertFalse(ClockGuard.isDeviceDateWrong(londonMidnight - 30_000, londonMidnight + 30_000, london))
    }

    @Test
    fun `changing only the time on the same day doesn't count`() {
        // 12:00 UTC is 13:00 in London; moving the clock to 20:00 the same day.
        assertFalse(ClockGuard.isDeviceDateWrong(noonUtc + 7 * hour, noonUtc, london))
    }

    @Test
    fun `certificate date failures are recognised through the exception chain`() {
        val expired = SSLHandshakeException("Chain validation failed").apply {
            initCause(CertificateException("Chain validation failed", CertificateExpiredException("expired")))
        }
        assertTrue(ClockGuard.isCertificateDateFailure(expired))

        val pathExpired = CertificateException(CertPathValidatorException("timestamp check failed", null, null, -1,
            CertPathValidatorException.BasicReason.EXPIRED))
        assertTrue(ClockGuard.isCertificateDateFailure(pathExpired))

        assertFalse(ClockGuard.isCertificateDateFailure(SSLHandshakeException("Connection reset")))
        assertFalse(ClockGuard.isCertificateDateFailure(CertificateException("untrusted root")))
    }
}
