# ADR-0001: Pure-Kotlin userspace TUN dataplane

## Status

Accepted (MVP)

## Context

System-wide mode requires real packet forwarding between TUN and the physical network with `VpnService.protect()`. Opaque prebuilt `.so` without reproducible sources is forbidden.

## Decision

Implement a pure-Kotlin userspace relay:

- Parse IPv4 (primary) / IPv6 (partial)
- TCP and UDP forwarding via NIO channels
- DNS handled in-process for PAER ordering
- Apache-2.0, fully in-repo, no NDK binary blob

## Alternatives considered

1. **hev-socks5-tunnel** — solid, but needs NDK packaging and careful license/ABI pinning.
2. **libv2ray / commercial stacks** — heavy, remote-proxy oriented, not aligned with local-only PAER.

## Consequences

- Full control and auditability.
- Simplified TCP state machine may not handle every edge protocol.
- Browser HTTPS over IPv4 is the primary success path for MVP.
