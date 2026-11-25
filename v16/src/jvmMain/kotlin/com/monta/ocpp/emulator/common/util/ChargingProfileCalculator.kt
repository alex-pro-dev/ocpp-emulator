package com.monta.ocpp.emulator.common.util

import com.monta.library.ocpp.common.chargingprofile.ChargingProfileKind
import com.monta.library.ocpp.common.chargingprofile.ChargingRateUnit
import com.monta.library.ocpp.common.chargingprofile.RecurrencyKind
import com.monta.library.ocpp.v16.smartcharge.ChargingProfile
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** A period's upper bound. Minimum charging rate is allocation guidance, not a floor. */
data class ActiveChargingLimit(
    val limit: Double,
    val unit: ChargingRateUnit,
    val numberPhases: Int,
) {
    fun watts(
        voltageV: Double = 230.0,
    ): Double = when (unit) {
        ChargingRateUnit.W -> limit
        ChargingRateUnit.A -> limit * numberPhases * voltageV
        else -> error("OCPP 1.6 charging limits must use W or A")
    }

    fun amperes(
        voltageV: Double = 230.0,
    ): Double = when (unit) {
        ChargingRateUnit.A -> limit
        ChargingRateUnit.W -> limit / (voltageV * numberPhases)
        else -> error("OCPP 1.6 charging limits must use W or A")
    }
}

object ChargingProfileCalculator {
    fun getWatts(
        transaction: ChargePointTransactionDAO,
    ): Double? = getWatts(transaction.chargingProfile, transaction.startTime)

    fun getWatts(
        chargingProfile: ChargingProfile?,
        fallbackScheduleStart: Instant,
        checkDate: Instant = Instant.now(),
    ): Double? = evaluate(chargingProfile, fallbackScheduleStart, checkDate)?.watts()

    fun isActive(
        chargingProfile: ChargingProfile,
        fallbackScheduleStart: Instant,
        checkDate: Instant = Instant.now(),
    ): Boolean = evaluate(chargingProfile, fallbackScheduleStart, checkDate) != null

    fun evaluate(
        chargingProfile: ChargingProfile?,
        fallbackScheduleStart: Instant,
        checkDate: Instant = Instant.now(),
    ): ActiveChargingLimit? {
        val profile = chargingProfile ?: return null
        if (validationError(profile) != null) return null
        if (profile.validFrom?.toInstant()?.let { checkDate < it } == true) return null
        if (profile.validTo?.toInstant()?.let { checkDate >= it } == true) return null

        val schedule = profile.chargingSchedule ?: return null
        val anchor = scheduleAnchor(profile, fallbackScheduleStart)
        // A future recurrence must not be projected backwards into the present.
        if (checkDate < anchor) return null

        val elapsed = Duration.between(anchor, checkDate).seconds
        val cycle = recurrenceSeconds(profile)
        val offset = if (cycle == null) elapsed else elapsed % cycle
        if (schedule.duration?.let { offset >= it.toLong() } == true) return null

        val period = schedule.chargingSchedulePeriod.lastOrNull {
            it.startPeriod!!.toLong() <= offset
        } ?: return null
        return ActiveChargingLimit(
            limit = period.limit!!,
            unit = schedule.chargingRateUnit!!,
            numberPhases = period.numberPhases,
        )
    }

    /**
     * The next possible limit/phase/applicability change, strictly after [after].
     * Repeated evaluation at the earliest boundary across profiles composes a
     * schedule without sampling and missing short periods.
     */
    fun nextTransition(
        chargingProfile: ChargingProfile?,
        fallbackScheduleStart: Instant,
        after: Instant,
    ): Instant? {
        val profile = chargingProfile ?: return null
        if (validationError(profile) != null) return null
        val schedule = profile.chargingSchedule ?: return null
        val validFrom = profile.validFrom?.toInstant()
        val validTo = profile.validTo?.toInstant()
        if (validTo != null && after >= validTo) return null

        var next: Instant? = null
        fun include(
            candidate: Instant,
        ) {
            if (candidate <= after) return
            if (validFrom != null && candidate < validFrom) return
            if (validTo != null && candidate > validTo) return
            if (next?.let { candidate < it } != false) next = candidate
        }

        validFrom?.let(::include)
        validTo?.let(::include)

        val anchor = scheduleAnchor(profile, fallbackScheduleStart)
        val cycle = recurrenceSeconds(profile)
        val cursor = maxOf(after, validFrom ?: after)
        val occurrence = if (cycle != null && cursor >= anchor) {
            val elapsedCycles = Duration.between(anchor, cursor).seconds / cycle
            anchor.plusSeconds(elapsedCycles * cycle)
        } else {
            anchor
        }
        val duration = schedule.duration?.toLong()
        val window = when {
            cycle == null -> duration
            duration == null -> cycle
            else -> minOf(cycle, duration)
        }

        for (period in schedule.chargingSchedulePeriod) {
            val offset = period.startPeriod!!.toLong()
            // A recurrence discards periods beyond its next restart.
            if (window == null || offset < window) include(occurrence.plusSeconds(offset))
        }
        window?.let { include(occurrence.plusSeconds(it)) }
        cycle?.let { include(occurrence.plusSeconds(it)) }
        return next
    }

    /** Required IDs, stack, purpose, transaction and connector ownership are checked by the handler. */
    fun validationError(
        profile: ChargingProfile,
    ): String? {
        val kind = profile.chargingProfileKind ?: return "chargingProfileKind is required"
        val schedule = profile.chargingSchedule ?: return "chargingSchedule is required"
        if (schedule.chargingRateUnit !in listOf(ChargingRateUnit.A, ChargingRateUnit.W)) {
            return "chargingRateUnit must be W or A"
        }
        if (schedule.duration?.let { it <= 0 } == true) return "duration must be positive"
        val validFrom = profile.validFrom?.toInstant()
        val validTo = profile.validTo?.toInstant()
        if (validFrom != null && validTo != null && validTo <= validFrom) {
            return "validTo must be later than validFrom"
        }
        when (kind) {
            ChargingProfileKind.Recurring -> {
                if (profile.recurrencyKind !in listOf(RecurrencyKind.Daily, RecurrencyKind.Weekly)) {
                    return "Recurring profiles require Daily or Weekly recurrencyKind"
                }
                if (schedule.startSchedule == null) return "Recurring profiles require startSchedule"
            }
            ChargingProfileKind.Relative -> {
                if (schedule.startSchedule != null) return "Relative profiles cannot have startSchedule"
                if (profile.recurrencyKind != null) return "recurrencyKind requires a Recurring profile"
            }
            ChargingProfileKind.Absolute -> {
                if (profile.recurrencyKind != null) return "recurrencyKind requires a Recurring profile"
            }
        }
        if (schedule.minChargingRate?.let { !validDecimal(it) } == true) {
            return "minChargingRate must be nonnegative with at most one decimal place"
        }
        if (schedule.chargingSchedulePeriod.isEmpty()) return "At least one period is required"

        var previous = -1
        for ((index, period) in schedule.chargingSchedulePeriod.withIndex()) {
            val start = period.startPeriod ?: return "startPeriod is required"
            if (start < 0 || start <= previous || (index == 0 && start != 0)) {
                return "Periods must start at zero and use strictly increasing nonnegative offsets"
            }
            if (period.limit?.let(::validDecimal) != true) {
                return "Period limits must be nonnegative with at most one decimal place"
            }
            if (period.numberPhases !in 1..3) return "numberPhases must be 1, 2, or 3"
            previous = start
        }
        return null
    }

    private fun scheduleAnchor(
        profile: ChargingProfile,
        fallbackScheduleStart: Instant,
    ): Instant = if (profile.chargingProfileKind == ChargingProfileKind.Relative) {
        fallbackScheduleStart
    } else {
        // validFrom only gates applicability; it never moves schedule offsets.
        profile.chargingSchedule?.startSchedule?.toInstant() ?: fallbackScheduleStart
    }

    private fun recurrenceSeconds(
        profile: ChargingProfile,
    ): Long? = if (profile.chargingProfileKind != ChargingProfileKind.Recurring) {
        null
    } else {
        when (profile.recurrencyKind) {
            RecurrencyKind.Daily -> Duration.ofDays(1).seconds
            RecurrencyKind.Weekly -> Duration.ofDays(7).seconds
            null -> null
        }
    }

    private fun validDecimal(
        value: Double,
    ): Boolean = value.isFinite() && value >= 0.0 && BigDecimal.valueOf(value).stripTrailingZeros().scale() <= 1
}
