# Test plan

## Unit (core:model)

See `PaerUnitTests` — 23 required cases including half-life decay, race limits, non-idempotent once, HTTP 4xx/5xx score policy, network-scoped preferences.

## Integration (manual / device)

1. Enable VPN → notification → browser HTTPS works.
2. Disable VPN → normal network restored.
3. Built-in test against `https://example.com/` — TLS OK.
4. Invalid cert host — rejected.
5. Clear RVEC / export metrics.
6. Rotate network (Wi-Fi/cellular) — new context, no SSID storage.
7. Revoke VPN permission — service stops cleanly.

## TUN smoke

1. Start service 2. Traffic via TUN 3. `protect()` sockets not re-captured 4. HTTPS response 5. Stop 6. Network OK.
