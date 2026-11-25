# EVCharge OCPP Emulator

[<img src="docs/icons/icon.png" alt="EVCharge" width="72">](https://evcharge.mobi)

A desktop charging station emulator from [EVCharge](https://evcharge.mobi) for developing and testing charging station management systems. The initial version uses OCPP 1.6J over WebSocket.

## Download

Get the installer for your system from [GitHub Releases](https://github.com/alex-pro-dev/ocpp-emulator/releases/latest). Each installer bundles a Java runtime.

| Platform | Package |
| --- | --- |
| Windows x64 | `.exe` |
| Linux x64 | `.deb` for Debian and Ubuntu, or `.rpm` for Fedora and other RPM-based distributions |
| macOS | `.dmg` for Apple silicon (`arm64`) or Intel (`x64`) |

The initial packages are unsigned and the macOS disk images are distributed without Apple notarization.

## Connect and simulate charging

1. Register a test station in the CSMS you're connecting to. Give each emulated station its own case-sensitive identity.
2. Select **Add charge point**. Enter the identity and choose a server, 1 to 32 connectors, and the station's maximum power in kW. Enter the station password if the CSMS uses HTTP Basic authentication.
3. Select **Create**, then use the station's **Connect** button or open the station. Repeat to run additional stations concurrently.
4. Open a connector to simulate plugging in a vehicle, authorize a session, stop a transaction, or send a **Connector Status** update. The station's message log shows traffic in both directions.

| Server preset | WebSocket base URL |
| --- | --- |
| EVCharge Staging | `wss://staging.evcharge.mobi:18443` |
| EVCharge Production | `wss://ocpp.evcharge.mobi/ocpp/16j/` |
| Local | `ws://localhost:8888/ocpp/1.6j/` |
| Custom | Your server's `ws://` or `wss://` endpoint |

The emulator appends the station identity when it is missing from the end of the URL path. Creating a station saves its settings locally. Opening the station or selecting **Connect** starts the connection.

During a transaction the simulator sends `MeterValues` with energy, power, current, and other configured measurands. Delivered power follows the station limit, simulated vehicle settings, and Smart Charging profiles. The default sampling interval is 180 seconds and can be changed using the OCPP `MeterValueSampleInterval` setting.

Smart Charging supports `SetChargingProfile`, `ClearChargingProfile`, `GetCompositeSchedule`, and transaction profiles in `RemoteStartTransaction`. See [Smart Charging behavior and limits](docs/smart-charging.md) for schedule evaluation, power sharing across connectors, and the electrical model.

The desktop app stores station settings, credentials, and logs in `.evcharge-ocpp-emulator` in your home directory. Keep its database and logs private when sharing test results.

## Build from source

Use JDK 25 and the included Gradle wrapper. The first build downloads dependencies from public repositories.

macOS or Linux

```sh
./gradlew :v16:run
./gradlew :common:test :v16:jvmTest
```

Windows PowerShell

```powershell
.\gradlew.bat :v16:run
.\gradlew.bat :common:test :v16:jvmTest
```

You can also launch the source build on Windows with `Launch-OCPP-Emulator.bat`. Run `:v16:packageDistributionForCurrentOS` with the wrapper to build the native installer for the host platform. Tagging the matching package version as `vX.Y.Z` runs the build matrix and publishes the installers.

For changes and fixes, open an issue or pull request and follow the [Code of Conduct](CODE_OF_CONDUCT.md).

## License and credits

Distributed under the [Apache License 2.0](LICENSE). This standalone project builds on the [Monta OCPP Emulator](https://github.com/monta-app/ocpp-emulator) and uses [Monta's OCPP library](https://github.com/monta-app/library-ocpp). EVCharge added its own server presets, installer and release workflow, charging simulation improvements, and station configuration.
