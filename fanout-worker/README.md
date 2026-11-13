# Template-publish fan-out worker

The Java MVP from the [MVP brief](../docs/01-brief/mvp-brief.md), built as the core of the Load
Dispatcher described in the [architecture](../docs/03-architecture.md#11-load-dispatcher-bulk-decisions-and-the-mvp-worker), §11. It is a library, not an application: ports are
interfaces, and the store and downstream system are faked in tests.

```
mvn test        # Java 21+, Maven 3.9+
```

## How one publish is processed

1. `PublishFanOut` turns the publish into durable items, one per engagement. The item id is
   derived from `(firm, product, version, engagement)` as `firm/product@seq/engagement`, so a redelivered publish event adds nothing.
2. `FanOutDispatcher` (one thread) starts an item only when three things hold: the firm is
   next in round-robin order, the `CapacityLimiter` grants a permit, and the `ItemStore`
   grants a lease.
3. `ItemProcessor` (one virtual thread per item) re-checks that the version is still the
   engagement's offer, submits to the downstream system with an idempotency key, then polls
   for the outcome while renewing the lease.
4. A redelivered item (after a crash, a restart or a timeout) first asks the downstream
   system what became of the earlier attempt, and adopts that answer before doing anything
   else.

## Where each concern lives

| Concern | Mechanism | Code |
|---|---|---|
| Concurrency | Virtual thread per in-flight item; exclusive, time-bounded leases with a fencing token | `FanOutDispatcher`, `ItemStore`, `Lease` |
| Downstream capacity | Global limit adapted by AIMD (halve on a 429, grow slowly while healthy), a per-firm cap, and a full admission pause for as long as a 429 asks, never longer than 15 minutes. Timeouts, unreadable statuses and submit transport errors are firm-scoped: they halve that firm's own cap (minimum 1), which climbs back by time alone, one step per increase interval, so an idle firm recovers too; only trouble from two or more firms in one cooldown window also lowers the global limit. `AimdConfig.fixed` switches adaptation off, firm caps included (a 429 still pauses). The limiter is in memory: see *Capacity bounds* below | `AimdCapacityLimiter`, `CapacityFeedback` |
| Fairness | Round robin across firms, one item per turn; a firm whose lease call fails is skipped for a doubling cool-off (cap 1 minute) so it cannot stall the others | `RoundRobinScheduler`, `FanOutDispatcher` |
| Idempotency | Deterministic item ids; ids restricted to `[A-Za-z0-9._-]{1,128}` so the joined form is unambiguous and safe to log; attempt id reused while the outcome is unknown, rotated only after a definite failure | `WorkItem`, `ItemId`, `RequeueReason` |
| Failure | Bounded deliveries, jittered exponential backoff, terminal `FAILED`; a stopping worker hands its items back at once with the delivery refunded (never below 1, so the next delivery still polls), and `close()` waits up to 10 s after interrupting for those hand-backs; a killed worker's items return when their lease expires; a delivery that never reached the downstream system (throttled, offer check or store down, status unreadable on a redelivery) is refunded, not spent, but only 20 times in a row (`maxDeferrals`), with a backoff that grows per deferral and is capped at the retry maximum, after which a throttled item or one whose offer check or lease renewal failed is `FAILED` and says why; an item whose status is unreadable on a redelivery is never failed this way, since an earlier delivery may have submitted it: it keeps being requeued with a warning; a status that cannot be read three times in a row releases the item and never fails it; a submit that fails because of a shutdown interrupt is handed back, not counted as a transport error; a downstream success that cannot be recorded, or that the capacity feedback chokes on, never fails the item, it is left to lease expiry and the next delivery finishes it; a rejection because the version no longer applies ends as `SKIPPED_OFFER_CHANGED`, any other as `FAILED` | `ItemProcessor`, `RetryPolicy`, `SubmitResult` |
| Correctness | Offer pin re-checked at dispatch time; stale leases cannot write | `OfferVerifier`, `ItemStore` |

## Ports to implement for production

| Port | Production adapter |
|---|---|
| `ItemStore` (= `ItemIntake` + `ItemLeasing` + `LeaseWriter`) | Regional Aurora table; lease with `FOR UPDATE SKIP LOCKED`, fenced writes with `WHERE lease_token = ?` |
| `EngagementGateway` | Engagement system bulk lane; must deduplicate on the attempt id, enforce the version pin, and time out its calls |
| `OfferVerifier` | Lookup against the per-branch offer map |
| `FanOutSource` | Query of pending engagements for the published version |

`InMemoryItemStore` (test sources) is the reference for the `ItemStore` contract, and
`InMemoryItemStoreTest` states that contract as tests.

## Capacity bounds

The limiter keeps its state in memory, so the global limit and the per-firm caps hold per
process. They bound the load on the downstream system only while a single dispatcher is
active; the architecture runs it behind leader election. Several workers on one store are
safe for exactly-once processing, because leases live in the store, but each has its own
limiter, so the limits multiply by the number of workers.

## Known limits

- An item whose attempts all time out ends as `FAILED` with detail "outcome unknown" (the wait is measured in elapsed time, so a wall-clock step does not cut it short). The
  attempt may still finish downstream later; the architecture's reconciliation (§10) is what
  corrects the record in that case.
- The offer check and the submit are not atomic, so the downstream system must reject a
  command for a version that no longer applies.

## Left out on purpose

Job cancellation, the monthly load-minute ceiling, completion events (the worker polls),
lane weights, metrics, and any framework wiring.
