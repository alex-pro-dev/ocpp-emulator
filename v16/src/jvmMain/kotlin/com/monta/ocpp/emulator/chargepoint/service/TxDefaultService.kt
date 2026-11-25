package com.monta.ocpp.emulator.v16.data.service

import com.monta.library.ocpp.common.chargingprofile.ChargingRateUnit
import com.monta.library.ocpp.v16.smartcharge.ChargingProfile
import com.monta.library.ocpp.v16.smartcharge.ChargingProfilePurposeType
import com.monta.library.ocpp.v16.smartcharge.ChargingSchedule
import com.monta.library.ocpp.v16.smartcharge.ChargingSchedulePeriod
import com.monta.library.ocpp.v16.smartcharge.ClearChargingProfileRequest
import com.monta.ocpp.emulator.chargepoint.entity.ChargePointDAO
import com.monta.ocpp.emulator.chargepointconnector.entity.ChargePointConnectorDAO
import com.monta.ocpp.emulator.chargepointconnector.model.CarState
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransaction
import com.monta.ocpp.emulator.chargepointtransaction.entity.ChargePointTransactionDAO
import com.monta.ocpp.emulator.common.util.ActiveChargingLimit
import com.monta.ocpp.emulator.common.util.ChargingProfileCalculator
import com.monta.ocpp.emulator.v16.data.entity.TxDefaultDAO
import com.monta.ocpp.emulator.v16.data.repository.StoredChargingProfile
import com.monta.ocpp.emulator.v16.data.repository.TxDefaultRepository
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Singleton
import kotlin.math.abs

data class EffectiveChargingLimit(
    val watts: Double,
    val ampsPerPhase: Double,
    val numberPhases: Int,
)

class PendingRemoteStartProfile internal constructor(
    internal val token: Long,
    internal val storedId: Long,
    val chargePointId: Long,
    val connectorId: Int,
    val profile: ChargingProfile,
)

@Singleton
class TxDefaultService(
    private val txDefaultRepository: TxDefaultRepository,
) {
    private val chargingStarts = ConcurrentHashMap<Long, Instant>()
    private val pendingLock = Any()
    private val pendingSequence = AtomicLong()
    private val pendingStarts = mutableMapOf<Long, PendingRemoteStartProfile>()

    fun store(
        chargePoint: ChargePointDAO,
        chargePointConnector: ChargePointConnectorDAO,
        chargingProfile: ChargingProfile,
    ): TxDefaultDAO = transaction {
        synchronized(pendingLock) {
            invalidateReplacedPending(chargePoint.id.value, chargePointConnector.position, chargingProfile)
            txDefaultRepository.store(chargePoint, chargePointConnector, chargingProfile)
        }
    }

    /** Store the accepted profile before asynchronous Authorize/StartTransaction responses. */
    fun reserveRemoteStartProfile(
        chargePoint: ChargePointDAO,
        connector: ChargePointConnectorDAO,
        profile: ChargingProfile,
    ): PendingRemoteStartProfile = transaction {
        synchronized(pendingLock) {
            invalidateReplacedPending(chargePoint.id.value, connector.position, profile)
            val stored = txDefaultRepository.store(chargePoint, connector, profile.copy(transactionId = null))
            PendingRemoteStartProfile(
                pendingSequence.incrementAndGet(),
                stored.id.value,
                chargePoint.id.value,
                connector.position,
                profile,
            ).also { pendingStarts[it.token] = it }
        }
    }

    fun isRemoteStartPending(
        pending: PendingRemoteStartProfile,
    ): Boolean = transaction {
        synchronized(pendingLock) { isPending(pending) }
    }

    fun installRemoteStartProfile(
        pending: PendingRemoteStartProfile,
        chargePoint: ChargePointDAO,
        connector: ChargePointConnectorDAO,
        session: ChargePointTransactionDAO,
    ): Boolean = transaction {
        synchronized(pendingLock) {
            val activeSession = ChargePointTransaction.selectAll().where {
                (ChargePointTransaction.id eq session.id) and
                    (ChargePointTransaction.chargePointId eq chargePoint.id) and
                    (ChargePointTransaction.connectorId eq connector.id) and
                    ChargePointTransaction.endTime.isNull()
            }.any()
            if (!isPending(pending) ||
                pending.chargePointId != chargePoint.id.value ||
                pending.connectorId != connector.position ||
                !activeSession ||
                !session.isOwner(connector)
            ) {
                return@synchronized false
            }
            txDefaultRepository.store(chargePoint, connector, pending.profile.copy(transactionId = session.externalId))
            pendingStarts.remove(pending.token)
            true
        }
    }

    fun finishRemoteStart(
        pending: PendingRemoteStartProfile,
    ) = transaction {
        synchronized(pendingLock) {
            if (isPending(pending)) TxDefaultDAO.findById(pending.storedId)?.delete()
            pendingStarts.remove(pending.token)
        }
    }

    fun cancelPendingRemoteStarts(
        chargePoint: ChargePointDAO,
    ) = transaction {
        synchronized(pendingLock) {
            pendingStarts.entries.removeAll { it.value.chargePointId == chargePoint.id.value }
            // An unbound TxProfile cannot survive a connection/boot boundary:
            // the asynchronous request which owned it no longer has a valid lease.
            txDefaultRepository.deleteUnboundTransactions(chargePoint)
        }
    }

    private fun isPending(
        pending: PendingRemoteStartProfile,
    ): Boolean {
        if (pendingStarts[pending.token] !== pending) return false
        val stored = TxDefaultDAO.findById(pending.storedId) ?: return false
        return stored.txDefaultProfile == pending.profile.copy(transactionId = null) &&
            stored.connector.position == pending.connectorId &&
            stored.chargePoint.id.value == pending.chargePointId
    }

    private fun invalidateReplacedPending(
        chargePointId: Long,
        connectorId: Int,
        profile: ChargingProfile,
    ) {
        pendingStarts.entries.removeAll { (_, pending) ->
            pending.chargePointId == chargePointId &&
                (
                    pending.profile.chargingProfileId == profile.chargingProfileId ||
                        (
                            pending.connectorId == connectorId &&
                                pending.profile.chargingProfilePurpose == profile.chargingProfilePurpose &&
                                pending.profile.stackLevel == profile.stackLevel
                            )
                    )
        }
    }

    fun listProfiles(
        chargePoint: ChargePointDAO,
    ): List<StoredChargingProfile> = transaction {
        txDefaultRepository.list(chargePoint)
    }

    fun clear(
        chargePoint: ChargePointDAO,
        connectorDAO: ChargePointConnectorDAO?,
        request: ClearChargingProfileRequest,
        stackLevel: Int? = null,
    ): Int = transaction {
        synchronized(pendingLock) {
            pendingStarts.entries.removeAll { (_, pending) ->
                pending.chargePointId == chargePoint.id.value &&
                    (connectorDAO == null || pending.connectorId == connectorDAO.position) &&
                    TxDefaultRepository.matches(pending.profile, request, stackLevel)
            }
            txDefaultRepository.delete(chargePoint, connectorDAO, request, stackLevel)
        }
    }

    fun deleteTransactionProfiles(
        chargePoint: ChargePointDAO,
        transactionId: Int,
    ): Int = transaction {
        txDefaultRepository.deleteTransaction(chargePoint, transactionId)
    }

    fun findActiveTxDefault(
        chargePoint: ChargePointDAO,
        chargePointConnector: ChargePointConnectorDAO,
        fallbackScheduleStart: Instant,
    ): ChargingProfile? = transaction {
        txDefaultRepository.findActive(
            chargePoint,
            chargePointConnector,
            fallbackScheduleStart,
            ChargingProfilePurposeType.TxDefaultProfile,
        )
    }

    fun findActiveChargePointMax(
        chargePoint: ChargePointDAO,
        fallbackScheduleStart: Instant,
    ): ChargingProfile? = transaction {
        txDefaultRepository.findActive(
            chargePoint,
            chargePoint.getConnector(0),
            fallbackScheduleStart,
            ChargingProfilePurposeType.ChargePointMaxProfile,
        )
    }

    /**
     * A deterministic local load balancer. Each connector uses the first N supply
     * phases (the existing emulator model has no configurable phase rotation).
     * Equal power is shared until a connector or a phase reaches its ceiling.
     */
    fun calculateChargingLimits(
        chargePoint: ChargePointDAO,
        checkDate: Instant = Instant.now(),
        forecastConnectorId: Int? = null,
        forecastStart: Instant = checkDate,
    ): Map<Int, EffectiveChargingLimit> = transaction {
        val connectors = chargePoint.getConnectors()
        val transactions = chargePoint.getActiveTransactions().associateBy { it.connectorPosition }
        val stored = txDefaultRepository.list(chargePoint)
        val globalStart = chargingStart(chargePoint, transactions.values.toList(), forecastStart)
        val station = select(stored, 0, ChargingProfilePurposeType.ChargePointMaxProfile, null, globalStart, checkDate)
        val stationPhases = station?.numberPhases ?: 3
        val phaseCeiling = station?.amperes() ?: Double.POSITIVE_INFINITY
        val stationWatts = minOf(
            nonnegative(chargePoint.maxKw * 1000.0),
            station?.watts() ?: Double.POSITIVE_INFINITY,
        )
        val demands = linkedMapOf<Int, EffectiveChargingLimit>()
        for (connector in connectors.sortedBy { it.position }) {
            val tx = transactions[connector.position]
            val start = tx?.startTime ?: forecastStart
            val entries = stored.toMutableList()
            // Older emulator versions kept a single transaction profile here.
            tx?.chargingProfile?.let { legacy ->
                val legacyId = legacy.chargingProfileId
                val legacySlot = legacy.stackLevel
                if (stored.none {
                        it.profile.chargingProfilePurpose == ChargingProfilePurposeType.TxProfile &&
                            (
                                it.profile.chargingProfileId == legacyId ||
                                    (it.connectorId == connector.position && it.profile.stackLevel == legacySlot)
                                )
                    }
                ) {
                    entries.add(
                        StoredChargingProfile(
                            connector.position,
                            legacy.copy(transactionId = tx.externalId),
                        ),
                    )
                }
            }
            val session = select(
                entries,
                connector.position,
                ChargingProfilePurposeType.TxProfile,
                tx?.externalId,
                start,
                checkDate,
            ) ?: select(
                entries,
                connector.position,
                ChargingProfilePurposeType.TxDefaultProfile,
                null,
                start,
                checkDate,
            )
            val phases = minOf(
                connector.vehicleNumberPhases.coerceIn(1, 3),
                session?.numberPhases ?: 3,
                stationPhases,
            )
            val consuming = (tx != null && connector.carState == CarState.C) ||
                forecastConnectorId == connector.position
            val amps = if (consuming) {
                minOf(
                    nonnegative(connector.vehicleMaxAmpsPerPhase),
                    nonnegative(connector.maxKw * 1000.0) / (230.0 * phases),
                    session?.amperes() ?: Double.POSITIVE_INFINITY,
                )
            } else {
                0.0
            }
            demands[connector.position] = EffectiveChargingLimit(amps * 230.0 * phases, amps, phases)
        }
        allocate(demands, stationWatts, phaseCeiling, stationPhases)
    }

    fun refreshChargingLimits(
        chargePoint: ChargePointDAO,
        checkDate: Instant = Instant.now(),
    ): Map<Int, EffectiveChargingLimit> = transaction {
        val limits = calculateChargingLimits(chargePoint, checkDate)
        for (connector in chargePoint.getConnectors()) {
            // Status uses the offered cap: zero EV demand must not look like an
            // EVSE restriction. Forecast a paused connector without reserving
            // capacity in the actual allocation returned to either meter path.
            val offered = if (connector.carState == CarState.B) {
                calculateChargingLimits(chargePoint, checkDate, forecastConnectorId = connector.position)[connector.position]
            } else {
                limits[connector.position]
            }
            connector.kw = (offered?.watts ?: 0.0) / 1000.0
        }
        limits
    }

    /** Kept for existing callers; this is now a station-wide allocated result. */
    fun findEffectiveWatts(
        chargePoint: ChargePointDAO,
        chargePointConnector: ChargePointConnectorDAO,
        transaction: ChargePointTransactionDAO?,
        fallbackScheduleStart: Instant,
    ): Double? = calculateChargingLimits(
        chargePoint,
        forecastConnectorId = chargePointConnector.position.takeIf { transaction == null },
        forecastStart = fallbackScheduleStart,
    )[chargePointConnector.position]?.watts

    fun compositeSchedule(
        chargePoint: ChargePointDAO,
        connectorId: Int,
        duration: Int,
        unit: ChargingRateUnit,
        checkDate: Instant = Instant.now(),
    ): ChargingSchedule = transaction {
        require(duration > 0) { "Duration must be positive" }
        require(unit == ChargingRateUnit.A || unit == ChargingRateUnit.W) { "Unsupported charging unit" }
        val connectors = chargePoint.getConnectors()
        require(connectorId == 0 || connectors.any { it.position == connectorId }) { "Unknown connector" }
        val origin = checkDate
        val end = origin.plusSeconds(duration.toLong())
        val activeTransactions = chargePoint.getActiveTransactions()
        val globalStart = chargingStart(chargePoint, activeTransactions, origin)
        val allProfiles = txDefaultRepository.list(chargePoint) + activeTransactions.mapNotNull { tx ->
            tx.chargingProfile?.let { StoredChargingProfile(tx.connectorPosition, it) }
        }
        val transitions = sortedSetOf<Instant>()
        val offsets = sortedSetOf(0, duration)
        for (entry in allProfiles) {
            val fallbacks = when {
                entry.profile.chargingProfilePurpose == ChargingProfilePurposeType.ChargePointMaxProfile ->
                    listOf(globalStart)
                entry.connectorId == 0 ->
                    // A global default is relative to each connector's own
                    // charging start, not the start of this composite request.
                    connectors.map { connector ->
                        activeTransactions.firstOrNull { it.connectorPosition == connector.position }?.startTime ?: origin
                    }.distinct()
                else -> listOf(
                    activeTransactions.firstOrNull { it.connectorPosition == entry.connectorId }?.startTime ?: origin,
                )
            }
            for (fallback in fallbacks) {
                var cursor = origin
                while (true) {
                    val next = ChargingProfileCalculator.nextTransition(entry.profile, fallback, cursor) ?: break
                    if (next >= end) break
                    require(next > cursor) { "Non-increasing profile transition" }
                    require(transitions.size < 50_000) { "Requested schedule has too many transitions" }
                    transitions.add(next)
                    val delta = java.time.Duration.between(origin, next)
                    val floor = delta.seconds.toInt()
                    offsets.add(floor)
                    offsets.add(floor + if (delta.nano > 0) 1 else 0)
                    cursor = next
                }
            }
        }

        data class Sample(val watts: Double, val amps: Double, val phases: Int)
        fun sample(
            instant: Instant,
        ): Sample {
            val limits = calculateChargingLimits(chargePoint, instant, connectorId.takeIf { it > 0 }, origin)
            if (connectorId == 0) {
                val phases = limits.values.maxOfOrNull { it.numberPhases } ?: 3
                return Sample(limits.values.sumOf { it.watts }, limits.values.sumOf { it.ampsPerPhase }, phases)
            }
            val selected = limits.getValue(connectorId)
            return Sample(selected.watts, selected.ampsPerPhase, selected.numberPhases)
        }

        val periods = mutableListOf<ChargingSchedulePeriod>()
        val pendingTransitions = ArrayDeque(transitions)
        for ((offset, nextOffset) in offsets.toList().zipWithNext()) {
            val from = origin.plusSeconds(offset.toLong())
            val until = origin.plusSeconds(nextOffset.toLong())
            while (pendingTransitions.firstOrNull()?.let { it <= from } == true) {
                pendingTransitions.removeFirst()
            }
            val samples = mutableListOf(sample(from))
            while (pendingTransitions.firstOrNull()?.let { it < until } == true) {
                samples.add(sample(pendingTransitions.removeFirst()))
            }
            // OCPP offsets are whole seconds. Within a fractional-edge bucket,
            // use the lowest limit/phase count: decreases start early, increases
            // start late, and the response never overstates an applicable cap.
            val phases = samples.minOf { it.phases }
            val amps = samples.minOf { it.amps }
            val watts = minOf(samples.minOf { it.watts }, amps * 230.0 * phases)
            val value = if (unit == ChargingRateUnit.W) watts else amps
            val encoded = java.math.BigDecimal.valueOf(value)
                .setScale(1, java.math.RoundingMode.FLOOR).toDouble()
            val previous = periods.lastOrNull()
            if (previous == null ||
                abs(requireNotNull(previous.limit) - encoded) > 0.000000001 ||
                previous.numberPhases != phases
            ) {
                periods.add(
                    ChargingSchedulePeriod(
                        startPeriod = offset,
                        limit = encoded,
                        numberPhases = phases,
                    ),
                )
            }
        }
        ChargingSchedule(
            duration = duration,
            startSchedule = origin.atZone(ZoneOffset.UTC),
            chargingRateUnit = unit,
            chargingSchedulePeriod = periods,
        )
    }

    private fun select(
        stored: List<StoredChargingProfile>,
        connectorId: Int,
        purpose: ChargingProfilePurposeType,
        transactionId: Int?,
        fallback: Instant,
        date: Instant,
    ): ActiveChargingLimit? = stored.withIndex().mapNotNull { indexed ->
        val entry = indexed.value
        val profile = entry.profile
        if (profile.chargingProfilePurpose != purpose) return@mapNotNull null
        if (purpose == ChargingProfilePurposeType.TxProfile) {
            if (entry.connectorId != connectorId ||
                transactionId == null ||
                profile.transactionId != transactionId
            ) {
                return@mapNotNull null
            }
        } else if (entry.connectorId != 0 && entry.connectorId != connectorId) {
            return@mapNotNull null
        }
        val limit = ChargingProfileCalculator.evaluate(profile, fallback, date) ?: return@mapNotNull null
        Triple(entry, limit, indexed.index)
    }.maxWithOrNull(
        compareBy<Triple<StoredChargingProfile, ActiveChargingLimit, Int>> { it.first.profile.stackLevel ?: 0 }
            .thenBy { it.first.connectorId == connectorId }
            .thenBy { it.third },
    )?.second

    private fun chargingStart(
        chargePoint: ChargePointDAO,
        active: List<ChargePointTransactionDAO>,
        fallback: Instant,
    ): Instant {
        val first = active.minOfOrNull { it.startTime }
        if (first == null) {
            chargingStarts.remove(chargePoint.id.value)
            return fallback
        }
        return chargingStarts.computeIfAbsent(chargePoint.id.value) {
            // Recover the beginning of a continuously occupied charging episode
            // on restart, including a first transaction that has already stopped.
            val history = ChargePointTransactionDAO.find {
                ChargePointTransaction.chargePointId eq chargePoint.id
            }.toList()
            var start: Instant = requireNotNull(first)
            var earlier: Instant
            do {
                earlier = start
                start = history.filter { it.startTime <= start && (it.endTime == null || it.endTime!! >= start) }
                    .minOfOrNull { it.startTime } ?: start
            } while (start < earlier)
            start
        }
    }

    private fun allocate(
        demands: Map<Int, EffectiveChargingLimit>,
        stationWatts: Double,
        phaseAmps: Double,
        stationPhases: Int,
    ): Map<Int, EffectiveChargingLimit> {
        val watts = demands.mapValues { 0.0 }.toMutableMap()
        var remainingWatts = stationWatts
        val remainingAmps = DoubleArray(3) { if (it < stationPhases) phaseAmps else 0.0 }
        var pending = demands.keys.filter { demands.getValue(it).watts > 0.0 }
        while (pending.isNotEmpty() && remainingWatts > 0.0000001) {
            var increment = minOf(
                remainingWatts / pending.size,
                pending.minOf { demands.getValue(it).watts - watts.getValue(it) },
            )
            for (phase in 0..2) {
                val factor = pending.filter { phase < demands.getValue(it).numberPhases }
                    .sumOf { 1.0 / (230.0 * demands.getValue(it).numberPhases) }
                if (factor > 0.0) increment = minOf(increment, remainingAmps[phase].coerceAtLeast(0.0) / factor)
            }
            if (increment <= 0.0000001) break
            for (id in pending) {
                val phases = demands.getValue(id).numberPhases
                watts[id] = watts.getValue(id) + increment
                for (phase in 0 until phases) {
                    remainingAmps[phase] -= increment / (230.0 * phases)
                }
            }
            remainingWatts = (remainingWatts - increment * pending.size).coerceAtLeast(0.0)
            pending = pending.filter { id ->
                val demand = demands.getValue(id)
                demand.watts - watts.getValue(id) > 0.0000001 &&
                    (0 until demand.numberPhases).all { remainingAmps[it] > 0.0000001 }
            }
        }
        return demands.mapValues { (id, demand) ->
            val power = watts.getValue(id).coerceAtMost(demand.watts)
            EffectiveChargingLimit(power, power / (230.0 * demand.numberPhases), demand.numberPhases)
        }
    }

    private fun nonnegative(
        value: Double,
    ): Double = if (value.isFinite()) value.coerceAtLeast(0.0) else 0.0
}
