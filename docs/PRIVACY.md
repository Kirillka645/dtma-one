# Privacy — DTMA One

- **No ads, analytics, crash SDKs, or developer telemetry.**
- **No remote server** of the project authors.
- RVEC stores: hostname, IP, port, transport, ALPN list, timestamps, minimal network context id.
- RVEC never stores: URL path/query, headers, cookies, tokens, certificates, payload, SSID/BSSID.
- Local logs: **off by default**.
- Metrics export: **manual only**, anonymized JSON.
- Test URL remembered only with explicit toggle.
- Android Backup: disabled / RVEC excluded.
