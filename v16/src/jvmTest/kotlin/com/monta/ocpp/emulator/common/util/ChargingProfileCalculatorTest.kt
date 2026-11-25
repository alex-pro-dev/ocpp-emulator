package com.monta.ocpp.emulator.common.util

import com.monta.library.ocpp.common.chargingprofile.ChargingProfileKind
import com.monta.library.ocpp.common.chargingprofile.ChargingRateUnit
import com.monta.library.ocpp.common.chargingprofile.RecurrencyKind
import com.monta.library.ocpp.v16.smartcharge.ChargingProfile
import com.monta.library.ocpp.v16.smartcharge.ChargingProfilePurposeType
import com.monta.library.ocpp.v16.smartcharge.ChargingSchedule
import com.monta.library.ocpp.v16.smartcharge.ChargingSchedulePeriod
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChargingProfileCalculatorTest {
    private val start = Instant.parse("2026-11-10T00:00:00Z")

    private fun period(
        offset: Int,
        limit: Double,
        phases: Int = 3,
    ) = ChargingSchedulePeriod(startPeriod = offset, limit = limit, numberPhases = phases)

    private fun profile(
        kind: ChargingProfileKind = ChargingProfileKind.Absolute,
        anchor: Instant? = start,
        recurrence: RecurrencyKind? = null,
        duration: Int? = null,
        validFrom: Instant? = null,
        validTo: Instant? = null,
        unit: ChargingRateUnit = ChargingRateUnit.A,
        minimum: Double? = null,
        periods: List<ChargingSchedulePeriod> = listOf(period(0, 16.0)),
    ) = ChargingProfile(
        chargingProfileId = 1,
        stackLevel = 0,
        chargingProfilePurpose = ChargingProfilePurposeType.TxDefaultProfile,
        chargingProfileKind = kind,
        recurrencyKind = recurrence,
        validFrom = validFrom?.atZone(ZoneOffset.UTC),
        validTo = validTo?.atZone(ZoneOffset.UTC),
        chargingSchedule = ChargingSchedule(
            duration = duration,
            startSchedule = anchor?.atZone(ZoneOffset.UTC),
            chargingRateUnit = unit,
            chargingSchedulePeriod = periods,
            minChargingRate = minimum,
        ),
    )

    private fun evaluate(
        profile: ChargingProfile,
        seconds: Long = 0,
        fallback: Instant = start,
    ) = ChargingProfileCalculator.evaluate(profile, fallback, start.plusSeconds(seconds))

    @Test
    fun `minimum charging rate cannot raise a zero upper bound`() {
        val profile = profile(minimum = 6.0, periods = listOf(period(0, 0.0)))
        val limit = assertNotNull(evaluate(profile))
        assertEquals(0.0, limit.limit)
        assertEquals(0.0, limit.watts())
        assertTrue(ChargingProfileCalculator.isActive(profile, start, start))
    }

    @Test
    fun `minimum charging rate cannot raise any upper bound`() {
        val limit = assertNotNull(evaluate(profile(minimum = 6.0, periods = listOf(period(0, 4.0)))))
        assertEquals(4.0, limit.amperes())
        assertEquals(2760.0, limit.watts())
    }

    @Test
    fun `amp limits retain decimal precision and are per phase`() {
        val limit = assertNotNull(evaluate(profile(periods = listOf(period(0, 8.6)))))
        assertEquals(8.6, limit.amperes())
        assertEquals(5934.0, limit.watts(), 0.000000001)
    }

    @Test
    fun `watt limits represent total power and convert using declared phases`() {
        val limit = assertNotNull(evaluate(profile(unit = ChargingRateUnit.W, periods = listOf(period(0, 11040.0)))))
        assertEquals(11040.0, limit.watts())
        assertEquals(16.0, limit.amperes())
        val singlePhase = assertNotNull(evaluate(profile(unit = ChargingRateUnit.W, periods = listOf(period(0, 3680.0, 1)))))
        assertEquals(16.0, singlePhase.amperes())
        assertEquals(3680.0, singlePhase.watts())
    }

    @Test
    fun `validFrom gates applicability without resetting schedule offsets`() {
        val profile = profile(
            anchor = null,
            validFrom = start.plusSeconds(1800),
            periods = listOf(period(0, 16.0), period(3600, 6.0)),
        )
        assertNull(evaluate(profile, 1799))
        assertEquals(16.0, evaluate(profile, 1800)?.limit)
        assertEquals(6.0, evaluate(profile, 3600)?.limit)
    }

    @Test
    fun `relative schedules use charging start instead of validFrom`() {
        val profile = profile(
            kind = ChargingProfileKind.Relative,
            anchor = null,
            validFrom = start.plusSeconds(1800),
            periods = listOf(period(0, 16.0), period(3600, 6.0)),
        )
        assertEquals(6.0, evaluate(profile, 3600)?.limit)
    }

    @Test
    fun `explicit absolute start takes precedence over transaction fallback`() {
        val profile = profile(anchor = start.plusSeconds(1800), periods = listOf(period(0, 16.0), period(3600, 6.0)))
        assertNull(evaluate(profile, 1799))
        assertEquals(16.0, evaluate(profile, 3600)?.limit)
        assertEquals(6.0, evaluate(profile, 5400)?.limit)
    }

    @Test
    fun `validTo and schedule duration are exclusive endpoints`() {
        assertNotNull(evaluate(profile(validTo = start.plusSeconds(60)), 59))
        assertNull(evaluate(profile(validTo = start.plusSeconds(60)), 60))
        assertNotNull(evaluate(profile(duration = 60), 59))
        assertNull(evaluate(profile(duration = 60), 60))
    }

    @Test
    fun `an omitted duration keeps the final period active`() {
        assertEquals(6.0, evaluate(profile(periods = listOf(period(0, 16.0), period(60, 6.0))), 1_000_000)?.limit)
    }

    @Test
    fun `a future recurring start is never projected into an earlier cycle`() {
        val profile = profile(
            kind = ChargingProfileKind.Recurring,
            recurrence = RecurrencyKind.Daily,
            anchor = start.plusSeconds(86400),
        )
        assertNull(evaluate(profile, 0))
        assertNull(evaluate(profile, 86399))
        assertNotNull(evaluate(profile, 86400))
    }

    @Test
    fun `daily duration gaps are inactive and restart at the configured anchor`() {
        val profile = profile(
            kind = ChargingProfileKind.Recurring,
            recurrence = RecurrencyKind.Daily,
            duration = 3600,
        )
        assertNotNull(evaluate(profile, 3599))
        assertNull(evaluate(profile, 3600))
        assertFalse(ChargingProfileCalculator.isActive(profile, start, start.plusSeconds(3600)))
        assertNotNull(evaluate(profile, 86400))
    }

    @Test
    fun `weekly cycles restart every seven days`() {
        val profile = profile(
            kind = ChargingProfileKind.Recurring,
            recurrence = RecurrencyKind.Weekly,
            periods = listOf(period(0, 16.0), period(86400, 6.0)),
        )
        assertEquals(6.0, evaluate(profile, 604799)?.limit)
        assertEquals(16.0, evaluate(profile, 604800)?.limit)
    }

    @Test
    fun `periods beyond a recurrence window are not executed`() {
        val profile = profile(
            kind = ChargingProfileKind.Recurring,
            recurrence = RecurrencyKind.Daily,
            duration = 172800,
            periods = listOf(period(0, 16.0), period(86400, 0.0)),
        )
        assertEquals(16.0, evaluate(profile, 86401)?.limit)
    }

    @Test
    fun `malformed schedules are rejected and never become active`() {
        val malformed = listOf(
            profile(periods = emptyList()),
            profile(periods = listOf(period(1, 16.0))),
            profile(periods = listOf(period(0, 16.0), period(0, 8.0))),
            profile(periods = listOf(period(0, 16.0), period(-1, 8.0))),
            profile(periods = listOf(period(0, -1.0))),
            profile(periods = listOf(period(0, Double.NaN))),
            profile(periods = listOf(period(0, Double.POSITIVE_INFINITY))),
            profile(periods = listOf(period(0, 8.66))),
            profile(periods = listOf(period(0, 0.00000001))),
            profile(periods = listOf(period(0, 16.0, 0))),
            profile(periods = listOf(period(0, 16.0, 4))),
            profile(duration = 0),
            profile(duration = -1),
            profile(unit = ChargingRateUnit.VAR),
            profile(minimum = -1.0),
            profile(kind = ChargingProfileKind.Relative),
            profile(recurrence = RecurrencyKind.Daily),
            profile(kind = ChargingProfileKind.Recurring, recurrence = RecurrencyKind.Daily, anchor = null),
            profile(validFrom = start, validTo = start),
        )
        for (profile in malformed) {
            assertNotNull(ChargingProfileCalculator.validationError(profile), profile.toString())
            assertNull(evaluate(profile), profile.toString())
        }
    }

    @Test
    fun `absolute transition boundaries are exact and strictly increasing`() {
        val profile = profile(
            duration = 120,
            validFrom = start.plusSeconds(30),
            validTo = start.plusSeconds(300),
            periods = listOf(period(0, 16.0), period(60, 6.0)),
        )
        var cursor = start
        for (seconds in listOf(30L, 60L, 120L, 300L)) {
            cursor = assertNotNull(ChargingProfileCalculator.nextTransition(profile, start, cursor))
            assertEquals(start.plusSeconds(seconds), cursor)
        }
        assertNull(ChargingProfileCalculator.nextTransition(profile, start, cursor))
    }

    @Test
    fun `transition lookup preserves subsecond boundaries`() {
        val anchor = start.plusNanos(500_000_000)
        val profile = profile(anchor = anchor, periods = listOf(period(0, 16.0), period(60, 6.0)))
        val boundary = anchor.plusSeconds(60)
        assertEquals(boundary, ChargingProfileCalculator.nextTransition(profile, start, boundary.minusNanos(1)))
        assertEquals(6.0, ChargingProfileCalculator.evaluate(profile, start, boundary)?.limit)
        assertNull(ChargingProfileCalculator.nextTransition(profile, start, boundary))
    }

    @Test
    fun `recurring transition lookup crosses inactive gaps and future initial starts`() {
        val profile = profile(kind = ChargingProfileKind.Recurring, recurrence = RecurrencyKind.Daily, duration = 3600)
        assertEquals(start.plusSeconds(3600), ChargingProfileCalculator.nextTransition(profile, start, start))
        assertEquals(start.plusSeconds(86400), ChargingProfileCalculator.nextTransition(profile, start, start.plusSeconds(3600)))
        val future = profile(kind = ChargingProfileKind.Recurring, recurrence = RecurrencyKind.Daily, anchor = start.plusSeconds(86400))
        assertEquals(start.plusSeconds(86400), ChargingProfileCalculator.nextTransition(future, start, start))
    }

    @Test
    fun `future validity can begin inside a recurring gap without scanning old cycles`() {
        val validFrom = start.plusSeconds(100L * 86400 + 7200)
        val profile = profile(
            kind = ChargingProfileKind.Recurring,
            recurrence = RecurrencyKind.Daily,
            duration = 3600,
            validFrom = validFrom,
        )
        assertEquals(validFrom, ChargingProfileCalculator.nextTransition(profile, start, start))
        assertNull(ChargingProfileCalculator.evaluate(profile, start, validFrom))
        assertEquals(start.plusSeconds(101L * 86400), ChargingProfileCalculator.nextTransition(profile, start, validFrom))
    }
}
