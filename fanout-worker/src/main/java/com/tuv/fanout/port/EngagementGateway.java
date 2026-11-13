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
 * Implementations must bound every call with a timeout: a call that never returns holds a
 * capacity permit for as long as it hangs.
 *
 * <p>The downstream system stays the last line of defence for the pin: it must reject a
 * command whose version is no longer applicable, because the worker's own offer check and
 * the submit are not atomic.
 */
public interface EngagementGateway {

    SubmitResult submit(ApplyCommand command);

    AttemptStatus status(AttemptId attemptId);
}
