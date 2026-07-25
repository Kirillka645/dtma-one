# Known limitations (MVP 0.1.0)

1. Not a bypass guarantee.
2. No TLS interception for third-party apps.
3. QUIC racing NOT_IMPLEMENTED (UDP still forwarded).
4. HTTPS/SVCB full record parsing not guaranteed on all Android DNS stacks.
5. ICMP not forwarded.
6. IPv6 TCP packet crafting on TUN is best-effort vs IPv4.
7. OkHttp cannot reuse raced TLS sockets for the app request; probes then single request.
8. Debug APK is for testing only (first MVP delivery).
9. Userspace TCP stack is simplified (good enough for common HTTPS/browser flows; not a full RFC793 implementation).
