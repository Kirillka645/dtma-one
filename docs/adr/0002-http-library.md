# ADR-0002: OkHttp for built-in HTTPS test

## Status

Accepted

## Decision

Use OkHttp 4.12 for the single application request after PAER TLS probes via `SSLSocket` with platform trust and HTTPS endpoint identification.

## Consequences

Documented limitation: no multi-channel TLS session reuse for the application request. Racing uses body-less probes.
