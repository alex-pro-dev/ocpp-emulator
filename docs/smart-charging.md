# OCPP 1.6 smart charging

The emulator evaluates the highest active stack for each profile purpose. A
transaction's `TxProfile` takes precedence over `TxDefaultProfile`; a connector
default takes precedence over a connector-0 default at the same stack level.
`ChargePointMaxProfile` then limits the station as a whole. Connector and vehicle
capabilities also constrain the result. A zero limit suspends charging;
`minChargingRate` never raises a schedule's upper bound.

Profiles with the same ID, or the same connector, purpose and stack level, replace
the previous profile. Multiple transaction-profile stacks are retained until the
transaction ends. Profiles supplied with `RemoteStartTransaction` are bound to
the confirmed transaction ID. Clear filters include ID, connector, purpose and
stack level; clearing without a match returns `Unknown`.

An accepted remote-start profile is reserved in the existing local profile table
while authorization and transaction confirmation are pending. A matching clear
removes that reservation and fences its delayed callback; a nonmatching clear
leaves it available. Disconnect, reset and boot discard unbound reservations.
The reservation counts toward installed-profile capacity, and an old callback
cannot replace a newer request using the same profile ID.

Absolute, Relative, Daily and Weekly schedules use their schedule or charging
start anchor. `validFrom` and `validTo` gate applicability without shifting that
anchor. The end of a duration or validity window is exclusive. A relative
station profile uses the start of the continuous station charging episode;
overlapping transactions do not reset its clock.

The local allocation strategy shares available watts equally among charging
connectors, redistributing unused capacity while respecting station total power
and current per phase. For example, two unconstrained connectors under an 11 kW
station limit receive 5.5 kW each. A connector needing only 3 kW leaves 8 kW for
the other connector, subject to its own limits. OCPP does not prescribe this
allocation strategy.

The electrical model uses nominal 230 V AC and assumes each connector uses the
first N supply phases; configurable phase rotation is not modeled. Amperes mean
current per phase. Watt profiles convert to current using the profile period's
declared phase count (three if omitted). A vehicle using fewer phases does not
receive the unused phases' current allowance.

Energy readings retain fractional watt-hours in the existing meter column. The
scheduler, triggered readings and transaction stop share one sampling clock, so
subsecond reads do not round energy away or count an interval twice. After the
initial persisted checkpoint, elapsed time uses a monotonic clock to avoid extra
or negative energy when the wall clock changes.

`GetCompositeSchedule` includes future profile, period, expiry and recurrence
changes. Connector 0 reports expected aggregate consumption; an idle physical
connector is forecast as charging for its own schedule request. Fractional-second
changes use conservative whole-second buckets, and output limits round down to
one decimal place. Requests exceeding 50,000 transition points are rejected
instead of returning a truncated schedule.

New charger configurations advertise `Core,SmartCharging`, stack levels through
255, up to 1,024 periods per schedule and 1,024 installed profiles, and both
`Current` and `Power` units. Incoming Set and RemoteStart profiles are validated
against the charger's advertised configuration. At startup, existing saved
chargers also gain `SmartCharging` in `SupportedFeatureProfiles`, preserving
their other feature flags and configuration values. This upgrade is persisted
before chargers connect. Missing capability keys receive the current defaults;
explicitly saved limits remain effective. Profile storage uses the existing
local tables and requires no schema migration.
Capability keys, including `SupportedFeatureProfiles` and the advertised Smart
Charging limits, are returned with `readonly: true` by `GetConfiguration`.
`ChangeConfiguration` rejects attempts to change these read-only keys; normal
editable settings remain writable. The startup upgrade can still update saved
capabilities internally. Refresh the server's charger configuration if it still
caches older values or read-only flags.

Protocol reference: Open Charge Alliance, OCPP 1.6 edition 2, sections 3.13, 5.3,
5.5, 5.7, 5.11, 5.16, 7.8–7.14, 9.1 and 9.4. These changes are covered by calculator and
in-memory database/handler regression tests; they are not an OCPP certification.
