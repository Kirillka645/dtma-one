# DTMA One

**Local Android VPN + PAER** (Passive Adaptive Endpoint Racing).  
Open source (Apache-2.0). No remote VPN server, proxy, relay, ads, or telemetry.

> **Not a bypass guarantee.** PAER may improve reachability only when at least one legitimate endpoint of the target is reachable.

## Features (MVP 0.1.0)

- One-button **Enable / Disable** local `VpnService`
- Real TUN dataplane: IPv4 TCP/UDP + managed DNS (`protect()` on sockets)
- **PAER**: limited endpoint race + **RVEC** + **Passive Hypothesis Engine**
- Built-in **strict HTTPS test** (platform TLS; invalid certs rejected)
- Material 3, system light/dark, **Russian + English**
- Encrypted RVEC (AES-GCM + Keystore-backed key material)

## Build

```bash
# JDK 17+, Android SDK
./gradlew test lintDebug assembleDebug assembleRelease
```

Debug APK:

`app/build/outputs/apk/debug/app-debug.apk`

## Install (test)

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Package id (debug): `app.dtma.one.debug`

## Docs

| Doc | Purpose |
|---|---|
| [docs/FEASIBILITY.md](docs/FEASIBILITY.md) | What is/isn't observable without TLS intercept |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules and pipeline |
| [docs/THREAT_MODEL.md](docs/THREAT_MODEL.md) | Threats and mitigations |
| [docs/PRIVACY.md](docs/PRIVACY.md) | Data handling |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Honest limits |
| [docs/SIGNING.md](docs/SIGNING.md) | Debug vs production signing |
| [docs/adr/](docs/adr/) | Architecture decisions |

## Security posture

- No user CA, no TLS decrypt, no trust-all TrustManager
- No port scanning / invented endpoints
- Non-idempotent application requests sent **once**
- RVEC excludes URL path/query/content; backup disabled

## License

Apache License 2.0 — see [LICENSE](LICENSE).
