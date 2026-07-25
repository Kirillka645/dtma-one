# ADR-0001: hev-socks5-tunnel + local SOCKS5 dataplane

## Status

Accepted (v0.2.0)

## Context

System-wide mode requires real packet forwarding between TUN and the physical network with `VpnService.protect()`. A pure-Kotlin toy TCP stack failed real apps (browsers, Telegram).

## Decision

Use reproducible open-source **hev-socks5-tunnel** (MIT, lwIP userspace stack) built from source via NDK:

1. `VpnService.Builder.establish()` provides TUN fd  
2. **hev-socks5-tunnel** runs tun2socks on that fd  
3. **LocalSocks5Server** (in-process) accepts hev connections on `127.0.0.1`  
4. All SOCKS5 egress sockets call `VpnService.protect()` / app is disallowed from VPN  

No remote proxy of the project authors. Sources live under `app/src/main/jni/` (vendored hev-socks5-tunnel tree).

## Alternatives considered

1. Pure-Kotlin TCP/IP — insufficient for production app traffic (0.1.x).  
2. libv2ray — heavier, remote-proxy oriented.  

## Consequences

- Reliable TCP/UDP for system apps (HTTPS, Telegram MTProto).  
- NDK required for build.  
- IPv6 capture deferred until dual-stack validation.
