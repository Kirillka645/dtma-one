# Known limitations

## Runtime system VPN (0.2.x — hev-socks5-tunnel)

1. **Same ISP egress in default mode.** Local SOCKS5 + `protect()` does not change the public path. If Telegram DC IPs time out (probe 0/N), Telegram will not work without an **external** path (user SOCKS5 / MTProto / other network).
2. **System-wide PAER is NOT implemented.** Endpoint racing / RVEC / full PAER apply to the **built-in HTTPS tester** only. System traffic is transparent tun2socks (TCP/UDP via hev → SOCKS5).
3. **No remote DTMA infrastructure.** Optional upstream SOCKS5 is **user-provided** only.
4. **No TLS interception / MITM.** Third-party certificates are not validated by DTMA One.
5. **QUIC endpoint racing:** NOT_IMPLEMENTED (UDP is forwarded by hev when SOCKS5 UDP works).
6. **Legacy `TunDataplane` / `SimpleDnsServer` / `ProtectedDnsClient`:** not used by `DtmaVpnService`; kept as experimental/dead code. Do not re-enable without fixing the audit list (remap, MTU, RST, retransmit, DNS ID, network callbacks).

## Telegram specifically

- Client often uses **hardcoded DC IPs** — DNS/PAER does not apply.
- Probe all DC timeouts on **one** network ⇒ try **multipath** (Wi‑Fi + LTE): DTMA binds Telegram DC sockets to the other underlying network (no SOCKS5).
- **Smart path (0.2.6+):** on Telegram DC CONNECT, race ports 443/80/5222 + networks; prefer probe cache. Same IP only (no cross-DC rewrite — breaks MTProto). Helps 2/N when 443 is dead but 80/5222 live.
- Probe 0/N on **all** paths ⇒ no pure-local fix; need MTProto / user SOCKS5 / other network / VPS.
- Workarounds without SOCKS5: smart path, multipath, official **MTProto** (`tg://proxy`), dual-SIM / other ISP.
- With infrastructure: **Settings → upstream SOCKS5** or self-hosted MTProto.

## Built-in HTTPS test

- OkHttp does not reuse raced TLS sockets for the application request (probe then single GET).
- Strict platform TLS only.
