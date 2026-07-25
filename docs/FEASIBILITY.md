# FEASIBILITY — DTMA One / PAER

## 1. What VpnService can observe without TLS interception

- DNS queries that pass through the managed DNS layer (VPN DNS at `10.0.0.1`).
- IP address, port, and transport (TCP/UDP) of flows seen on the TUN.
- Transport state: connect success/failure, reset, timeout, close.
- Byte volume and direction at the IP/TCP/UDP layer.
- Connection establishment and teardown.

## 2. What cannot be reliably determined for third-party apps

- Hostname verification result **inside** the third-party app.
- Exact TLS alert reason.
- Whether an RST was forged by a middlebox vs genuine endpoint.
- Exact application-level failure cause.
- Hostname when ECH, external DoH/DoT, or no DNS context is available.
- Certificate validity for connections not initiated by DTMA One.

**UI rule:** never show “certificate verified by DTMA One” for third-party traffic.

## 3. What is implemented where

### System-wide VpnService mode (0.2.x — hev-socks5-tunnel)

| Capability | Status |
|---|---|
| Real TUN establish + foreground notification | Yes |
| IPv4 TCP/UDP via hev (lwIP) + SOCKS5 egress | Yes |
| Local SOCKS5 with `protect()` (same ISP) | Yes |
| Optional **user** upstream SOCKS5 | Yes (Settings) |
| System-wide PAER / endpoint race on TUN | **No** (PAER is built-in HTTPS tester only) |
| Managed DNS with PAER-ordered A/AAAA + IP remap | **No** (legacy TunDataplane/SimpleDns; not wired) |
| DNS servers on VPN interface | Underlying network DNS preferred; public fallback |
| IPv6 TUN route | Best-effort when device supports |
| Full TLS validation of third-party traffic | **No** (impossible without MITM) |
| QUIC/HTTP/3 endpoint racing | **NOT_IMPLEMENTED** |
| ICMP forwarding | Via hev/lwIP as stack allows; not a separate PAER path |

### Built-in HTTPS client (DTMA One)

| Capability | Status |
|---|---|
| Connect to specific IP with original hostname SNI | Yes |
| Platform certificate chain + hostname verification | Yes |
| Reject invalid/self-signed certs | Yes |
| ALPN observation when platform exposes it | Yes |
| Limited endpoint race (connect+TLS probes) | Yes |
| Single application request to winner only | Yes |
| Reuse of pre-opened TLS channel for app request | **No** (see OkHttp note) |

## 4. HTTP library check (OkHttp 4.12)

| Question | Answer |
|---|---|
| Connect to specific IP while keeping hostname? | Yes, via custom `Dns` returning one `InetAddress` |
| Correct SNI? | Yes (hostname in URL / SSL socket) |
| Cert check for original hostname? | Yes (platform TrustManager; `endpointIdentificationAlgorithm=HTTPS` on probes) |
| Control when HTTP request is sent? | Yes |
| Cancel losing connections? | Yes (coroutine cancel / close sockets) |
| Reuse selected TLS channel for request? | **Not reliably** without custom low-level stack |
| Observe IP / ALPN / transport? | IP yes; ALPN best-effort; transport TCP |

**Design choice:** PAER for the built-in client races **safe connect+TLS probes without user request body**, then sends **one** application GET to the winner. This avoids duplicating non-idempotent requests and does not fake multi-channel reuse.

## 5. When PAER can help / cannot help

**May help:** partial IP reachability, IPv4/IPv6 asymmetry, temporary DNS issues, sticky RVEC success, first route unlucky.

**Cannot help:** full address-space block, null-route, no route, link down, authoritative service rejection for all paths, complete DNS blackhole without usable RVEC.

## 6. Security non-goals (enforced)

No user CA, no TLS decrypt, no trust-all TrustManager, no port scanning, no invented endpoints, no developer telemetry/ads, no request body duplication for non-idempotent methods.
