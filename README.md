# DTMA One

**Local Android VPN** (hev-socks5-tunnel / lwIP tun2socks) + **PAER** tools for the **built-in HTTPS test**.  
Open source (Apache-2.0). No remote VPN/proxy owned by this project.

> **Not a bypass guarantee.** If every legitimate IP of a service is unreachable from your ISP, a local VPN that exits via the same ISP cannot help.

## What system VPN does (0.2.x)

```
Apps → VpnService TUN → hev-socks5-tunnel → SOCKS5 egress → Internet
```

- **Default egress:** in-process SOCKS5 with `VpnService.protect()` (same ISP).
- **Optional egress:** **your** upstream SOCKS5 (Settings) when DCs/sites are blocked on ISP path.
- Real TCP/UDP stack: **hev** (not the legacy pure-Kotlin `TunDataplane`).

## What PAER does

- **Built-in connection test only:** limited endpoint race, RVEC, Passive Hypothesis Engine, strict TLS.
- **Not** applied as transparent remap for all apps in system mode (by design after 0.2; avoids anti-CER “force first IP” bugs).

## Features

- One-button Enable / Disable
- hev tun2socks + local or user SOCKS5
- Telegram DC probe (diagnostics)
- Material 3, RU/EN
- Encrypted RVEC for the HTTPS-test path

## Build

```bash
# JDK 17+, Android SDK, NDK 27.x (for hev-socks5-tunnel)
./gradlew test assembleDebug
```

Package (debug): `app.dtma.one.debug`

## Telegram / blocked DCs

1. Run **Проверка → Проверить DC Telegram**.
2. If **0/N reachable** → local mode cannot open Telegram; set **your SOCKS5** in Settings or use MTProto inside Telegram.
3. See [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md).

## Docs

| Doc | Purpose |
|---|---|
| [docs/FEASIBILITY.md](docs/FEASIBILITY.md) | Observability without TLS intercept |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Modules |
| [docs/AUDIT_CLAUDE_RESPONSE.md](docs/AUDIT_CLAUDE_RESPONSE.md) | Fact-check of TunDataplane audit |
| [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) | Honest limits |
| [docs/adr/0001-tun-dataplane.md](docs/adr/0001-tun-dataplane.md) | Why hev |

## License

Apache License 2.0 — see [LICENSE](LICENSE).  
hev-socks5-tunnel: MIT (vendored under `app/src/main/jni/`).
