package com.tuv.fanout.port;

/**
 * Durable queue of fan-out items. It is the single source of truth for item state, which
 * is what makes the worker stateless and resumable after a crash.
 *
 * <p>Every method is atomic and safe to call from many workers and processes. Callers pass
 * the current time so behaviour is testable; an implementation shared by several processes
 * should compare against its own clock (for example the database's) so that clock skew
 * between workers cannot change who owns an item.
 */
public interface ItemStore extends ItemIntake, ItemLeasing, LeaseWriter {
}
