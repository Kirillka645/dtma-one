# Threat model — DTMA One

## Assets

- Local RVEC endpoint cache
- User network traffic (transit only; not logged by default)
- VPN permission state

## Trust boundaries

- Android system TLS trust store (not replaced)
- Platform DNS for candidate discovery
- No developer backend

## Threats & mitigations

| Threat | Mitigation |
|---|---|
| MITM via custom CA | Forbidden; system CAs only |
| Trust-all TLS | Forbidden |
| RVEC path leakage | Hostname only; path/query rejected |
| Backup exfiltration of RVEC | `allowBackup=false` + exclude rules |
| Traffic loop TUN→socket→TUN | Mandatory `protect()` |
| Non-idempotent request duplication | `ApplicationRequestGuard` |
| Port scanning | Only announced/standard ports |
| Silent telemetry | None present |

## Out of scope

Defeat of nation-state full path blocks, ECH-aware hostname recovery, third-party cert validation without interception.
