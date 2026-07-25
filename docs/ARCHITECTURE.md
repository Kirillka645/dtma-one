# Architecture — DTMA One

## Modules

```
app/                 UI (Compose) + DtmaVpnService
core/model/          Pure Kotlin: scoring, race limits, PHE, RVEC policy
core/network/        DNS, StrictHttpsTester, TUN dataplane
core/storage/        Encrypted RVEC (Room + AES-GCM), DataStore settings
```

## PAER pipeline

1. Fresh A/AAAA (and optional HTTPS/SVCB when available)
2. Merge non-expired RVEC (never displace all DNS)
3. Score + sort
4. Limited race (≤3 simultaneous, ≤4 attempts, delayed starts)
5. Winner selection
6. Cancel losers without failure penalty
7. Single application request (built-in client only)
8. Update RVEC + Passive Hypothesis Engine
9. At most one retry round; else cooldown

## TUN dataplane (ADR-0001)

Pure-Kotlin userspace relay:

- Read IP packets from `VpnService.Builder.establish()`
- DNS to `10.0.0.1` → `SimpleDnsServer` (PAER order)
- TCP/UDP → `SocketChannel`/`DatagramChannel` with `protect()`
- NAT presentation: remapped destination answers appear as original destination when hostname was bound via managed DNS

## Network context

Type (Wi-Fi/cellular/Ethernet), temporary Android Network id, IPv4/IPv6 presence, hashed DNS fingerprint. No SSID/BSSID/MAC/IMEI/IMSI.
