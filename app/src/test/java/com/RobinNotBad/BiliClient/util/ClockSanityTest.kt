package com.RobinNotBad.BiliClient.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException

class ClockSanityTest {

    /** 2026-10-27 00:00:00 UTC */
    private val build261027 = 1_793_059_200_000L

    @Test
    fun buildDate_parsesVersionCode() {
        assertEquals(build261027, ClockSanity.buildDateMillis(2610270))
    }

    @Test
    fun buildDate_rejectsMalformed() {
        assertNull(ClockSanity.buildDateMillis(0))
        assertNull(ClockSanity.buildDateMillis(123))
        assertNull(ClockSanity.buildDateMillis(2613010)) // 13 月
        assertNull(ClockSanity.buildDateMillis(2602300)) // 2 月 30 日
    }

    @Test
    fun clockBehind_1970IsBehind() {
        assertTrue(ClockSanity.isClockBehind(0L, 2610270))
    }

    @Test
    fun clockBehind_buildDayAndLaterAreFine() {
        assertFalse(ClockSanity.isClockBehind(build261027, 2610270))
        assertFalse(ClockSanity.isClockBehind(build261027 + 400L * 86_400_000, 2610270))
    }

    @Test
    fun clockBehind_toleratesTimezone() {
        // 西半球用户在构建当天看到的本地时间可能比 UTC 0 点早十几个小时，不该报
        assertFalse(ClockSanity.isClockBehind(build261027 - 12L * 3_600_000, 2610270))
        assertTrue(ClockSanity.isClockBehind(build261027 - 2L * 86_400_000, 2610270))
    }

    @Test
    fun clockBehind_unknownVersionNeverWarns() {
        assertFalse(ClockSanity.isClockBehind(0L, 1))
    }

    @Test
    fun skewed_bothDirections() {
        val server = build261027
        assertTrue(ClockSanity.isSkewed(server + 5L * 365 * 86_400_000, server)) // 拨到未来
        assertTrue(ClockSanity.isSkewed(0L, server)) // 回到 1970
    }

    @Test
    fun skewed_smallDriftIsFine() {
        val server = build261027
        assertFalse(ClockSanity.isSkewed(server, server))
        assertFalse(ClockSanity.isSkewed(server + 10L * 60_000, server))
        assertFalse(ClockSanity.isSkewed(server - 59L * 60_000, server))
    }

    @Test
    fun certTimeError_detectsWrappedCause() {
        val e = SSLHandshakeException("handshake failed")
        e.initCause(CertificateNotYetValidException("not yet"))
        assertTrue(ClockSanity.isCertTimeError(e))
        assertTrue(ClockSanity.isCertTimeError(IOException(CertificateExpiredException())))
    }

    @Test
    fun certTimeError_detectsByMessage() {
        val e = SSLHandshakeException("x")
        e.initCause(CertPathValidatorException("timestamp check failed"))
        assertTrue(ClockSanity.isCertTimeError(e))
    }

    @Test
    fun certTimeError_ignoresOtherErrors() {
        assertFalse(ClockSanity.isCertTimeError(null))
        assertFalse(ClockSanity.isCertTimeError(IOException("timeout")))
        assertFalse(ClockSanity.isCertTimeError(
            SSLHandshakeException("Trust anchor for certification path not found.")
        ))
    }
}
