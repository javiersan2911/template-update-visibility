package com.tuv.fanout.port;

import com.tuv.fanout.domain.AttemptId;

/**
 * The downstream engagement system, owned by another team. Processing one engagement takes
 * about a minute, so the call is asynchronous: submit, then observe the outcome.
 *
 * <p>Contract the downstream system must honour:
 * <ul>
 *   <li>{@link #submit} is idempotent on {@link ApplyCommand#attemptId()}: a repeated key
 *       never processes the engagement a second time and reports {@code Accepted}.</li>
 *   <li>{@link #status} for an accepted key eventually becomes terminal and stays so.</li>
 *   <li>{@link AttemptStatus#NOT_FOUND} means the key was never accepted. A downstream
 *       system that can report NOT_FOUND for an accepted key beyond the short grace (the
 *       first three reads in a row after this delivery's own accepted submit) can cause a
 *       resubmit under the same key (safe) or, if the offer changed meanwhile, a
 *       SKIPPED_OFFER_CHANGED for an engagement that was in fact updated.</li>
 * </ul>
 * Both methods may throw on transport failure; the outcome is then unknown to the caller.
 * Implementations should still set their own timeouts, which can fail faster and release their
 * connections properly. The worker nevertheless enforces {@code DispatcherConfig.callTimeout()}
 * on every call, whatever the adapter does: a call that has not returned by then is interrupted
 * and treated as one that threw, so a submit has an unknown outcome and a status is unreadable.
 *
 * <p>Known residuals: a call that ignores interruption keeps its virtual thread until it
 * returns, and a submit that is accepted after its deadline still takes effect; it is safe
 * only because the next delivery reuses the same idempotency key. The deadline includes
 * scheduling delay, not only the call's own time. An adapter that blocks while pinning its
 * carrier thread (inside {@code synchronized} on JDK 21, or in native I/O) can starve other
 * virtual threads. Abandoned calls are not counted against any capacity permit. The same
 * holds for the item store: a {@code leaseNext} that commits after its deadline leaves an item
 * leased until the lease expires and has spent one of its deliveries, so a store that is
 * persistently slower than the call timeout can exhaust an item's delivery budget without an
 * attempt; adapters should enforce a shorter statement timeout that rolls back.
 *
 * <p>The downstream system stays the last line of defence for the pin: it must reject a
 * command whose version is no longer applicable, because the worker's own offer check and
 * the submit are not atomic.
 */
public interface EngagementGateway {

    SubmitResult submit(ApplyCommand command);

    AttemptStatus status(AttemptId attemptId);
}
