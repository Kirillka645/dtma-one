# Перепроверка аудита Claude (TunDataplane / DNS / Telegram)

Дата факт-чека: код ветки `main` + незакоммиченные правки **0.2.3**.

## Главный вывод (одной фразой)

**Почти все пункты аудита технически верны для legacy pure-Kotlin стека (`TunDataplane` / `SimpleDnsServer` / `ProtectedDnsClient`), но этот стек НЕ является runtime VPN с 0.2.0.**  
Активный путь: `DtmaVpnService` → **hev-socks5-tunnel (lwIP)** → `LocalSocks5Server` или user upstream SOCKS5.

Чинить «MTU / RST / firstOrNull remap / busy-wait» в `TunDataplane` **не починит Telegram**, потому что Telegram туда не ходит.

Реальная причина **probe 0/8** у пользователя: **IP дата-центров Telegram недоступны с ISP** (timeout/ENETUNREACH). Локальный protect SOCKS5 выходит тем же путём → ноль пользы без **внешнего** SOCKS5/MTProto/другой сети.

---

## Матрица: пункт аудита → legacy → активный 0.2.x

| # | Утверждение Claude | Legacy (`TunDataplane`/DNS) | Runtime VPN (hev 0.2.x) | Вердикт |
|---|---|---|---|---|
| **1.1** | `selectDestination` = first IP + `ipToHost` коллизии + port ignore | **Да, баги были.** Remap + `DnsSessionCache.ipToHost` перезапись + `port=443` в SimpleDns. | **Не применяется.** `DtmaVpnService` **не** вызывает `TunDataplane` / `selectDestination`. Default remap в legacy даже passthrough: `{ _, ip, _ -> ip }`. | Claude прав про **старый** код; для Telegram на 0.2.x **не root cause**. |
| **1.2** | DNS только 1.1.1.1/8.8.8.8, sequential A+AAAA, pool=2 | **Да** в `ProtectedDnsClient` + `dnsPool(2)`. | **Частично.** Системный DNS = `addDnsServer` на TUN; **0.2.3** берёт DNS с underlying network, fallback 1.1.1.1/8.8.8.8. Нет своего intercept DNS-сервера 10.0.0.1. | Hardcoded upstreams — legacy. Активный DNS — OS + hev. |
| **1.3** | Fixed TXID `0x1234`, нет валидации ответа | **Да**, `ProtectedDnsClient` строка `putShort(0x1234)`. | Не в hot path VPN. | Баг legacy DNS-клиента; критично только если снова включить. |
| **1.4** | Payload MTU+headers → 1540, нет retransmit | **Частично устарело.** Сейчас `MAX_TCP_PAYLOAD = 1360` (уже под MTU). Retransmit / unacked queue — **всё ещё нет**. | hev/lwIP — свой TCP. MTU VPN/hev = **1400**. | Oversize packet — **уже смягчено** в legacy; retransmit — да, дыра legacy. Не про hev. |
| **1.5** | `writePacket` drop oldest on full queue | **Да:** `if (!outbound.offer) { poll(); offer() }`. | hev write path, не этот queue. | Верно для legacy; drop без retransmit = hang. |
| **1.6** | Нет RST клиенту; `ipv4TcpRst` мёртвый | **Да**, `PacketBuilder.ipv4TcpRst` существует, вызовов из dataplane нет. | hev/lwIP шлёт RST/FIN по своему стеку. | Верно для legacy. |
| **1.7** | Нет `setUnderlyingNetworks` / NetworkCallback | **Было верно** до 0.2.3. | **Исправлено в 0.2.3:** `UnderlyingNetwork` + `registerNetworkCallback` + `setUnderlyingNetworks`. | Claude был прав; **закрыто** на активном пути. |
| **1.8** | AAAA отдаётся, IPv6 не в туннеле | **Да** для SimpleDns + IPv4-only TunDataplane. | **0.2.x:** IPv6 TUN address + route `::/0` best-effort; hev dual-stack если yaml ipv6. | Legacy — согласны. Active — не «молча дроп IPv6». |
| **2.1–2.9** | Window, SYN-RECEIVED, OOO, busy-wait sleep(1), RST unused, ICMP, setMetered(false), bumpFlows, dual checksum | В основном **да** для userspace Kotlin TCP. Busy-wait: `Thread.sleep(1)` в pump. | hev: Selector/task model, не Kotlin busy-wait. **setMetered:** 0.2.3 наследует `underlying.metered`. | Корректный разбор **мертвого** стека. |
| **3.x** | DNS NODATA для TXT/SRV, EDNS, CNAME, ServFail counters, compression, TTL 60, getByName literal | **Да** в `SimpleDnsServer` / `ProtectedDnsClient`. | Не используется в VPN. | Legacy only. |
| **4** | README system PAER vs code | **Было расхождение.** | README / KNOWN_LIMITATIONS: PAER = **HTTPS tester only**; system = transparent tun2socks. | Согласны; доки выровнены. |

---

## Что из «порядка починки Telegram» Claude реально надо / не надо

| Шаг Claude | Нужно для Telegram на 0.2.x? |
|---|---|
| 1. Отключить remap | Уже не в пути. Диагностика «passthrough vs remap» **неделит** hev-сборку. |
| 2. MTU−40 | Уже 1360 в legacy; hev mtu 1400. |
| 3. Backpressure vs drop | Только если вернуть TunDataplane. |
| 4. RST + connect timeout | Только legacy. |
| 5–6. DNS system/parallel/ID/proxy types | Только если вернуть SimpleDns. Active: underlying DNS (0.2.3). |
| 7. NetworkCallback | **Сделано 0.2.3.** |
| 8. Selector вместо sleep(1) | hev already. |
| 9. Retransmit | hev/lwIP. |

**Реальный обход Telegram при 0/8:** Settings → **user upstream SOCKS5**, MTProto proxy в Telegram, или сеть где probe > 0.

---

## Где Claude мог «промахнуться» (не баг, а контекст)

1. **Читал ключевые исходники legacy**, не заметив (или не акцентировав), что `DtmaVpnService` с 0.2.0 на hev — ADR-0001.
2. Сниппет `selectDestination = { hostname → firstOrNull() }` — **исторический** wiring 0.1.x; в текущем `TunDataplane` default = passthrough, а service его не создаёт.
3. `buf = ByteBuffer.allocate(MTU)` ещё есть, но **payload cap** `MAX_TCP_PAYLOAD=1360` — oversize не «гарантирован» как в старой версии аудита.
4. Для **Telegram hardcode DC** Claude прав: PAER/DNS remap **ноль пользы** — и в legacy, и сейчас.

---

## Активный путь (что реально крутится)

```
Apps
  → VpnService TUN (mtu 1400, IPv4 + optional IPv6)
  → hev-socks5-tunnel (native lwIP tun2socks)
  → SOCKS5 127.0.0.1:18080 (LocalSocks5 + protect)
     OR user upstream SOCKS5 (Settings)
  → Internet (same ISP | remote proxy)
```

Логи: `adb logcat -s DtmaVpnService:V LocalSocks5:V`  
Probe: в приложении Telegram DC probe; при `0/N` — только внешний путь.

---

## Legacy: статус кода

Файлы помечены `LEGACY / NOT USED by DtmaVpnService`:

- `core/network/.../tun/TunDataplane.kt`
- `SimpleDnsServer.kt`, `DnsSessionCache.kt`
- `dns/ProtectedDnsClient.kt`

**Не удаляем** сразу (история/эксперименты), **не подключаем** без полного checklist из этого файла.

---

## Документация vs код (блок 4)

| Утверждение | Статус |
|---|---|
| README: system PAER | **Исправлено:** PAER только built-in HTTPS test |
| FEASIBILITY: managed DNS PAER + remap | **Обновить** под hev (transparent, no system PAER) |
| No user CA / no TLS decrypt | **Честно** |
| KNOWN_LIMITATIONS: retransmit/RST/fixed DNS | **Для legacy** перечислено; для runtime — same ISP + no system PAER + optional upstream |

---

## Итог для пользователя

1. Аудит Claude — **хороший разбор мёртвого Kotlin TCP/DNS стека**.  
2. Он **не объясняет** «интернет есть, Telegram 0/8» на сборке **0.2.x**.  
3. Объясняет **почему 0.1.x hangs** (если кто-то ещё на pure-Kotlin dataplane).  
4. Telegram: **probe 0/8 = ISP block** → upstream SOCKS5 / MTProto / другая сеть.  
5. 0.2.3: NetworkCallback, underlying DNS, setMetered(real), доки/баннеры legacy.
