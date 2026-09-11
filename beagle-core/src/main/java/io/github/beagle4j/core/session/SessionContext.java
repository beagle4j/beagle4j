package io.github.beagle4j.core.session;

/**
 * Holds the session in scope for the current thread.
 *
 * <p>A plain {@link ThreadLocal}, not an {@code InheritableThreadLocal}. Inheritance
 * would look convenient -- child threads would pick up the session for free -- but the
 * threads in question are almost always pooled, and a pooled thread inherits the
 * session belonging to whichever request happened to create it, then keeps that stale
 * reference for the lifetime of the pool. The result is queries attributed to the wrong
 * request and a session that is never collected. Explicit propagation via
 * {@link #wrap(Runnable)} is more typing and is correct.
 *
 * <p>{@code ScopedValue} (JEP 446) is the better long-term answer here, especially under
 * virtual threads, but it is still a preview API on the Java 17 baseline this library
 * targets. The indirection in this class is what will let that swap happen without
 * touching any caller.
 */
public final class SessionContext {

    private static final ThreadLocal<BeagleSession> CURRENT = new ThreadLocal<>();

    private SessionContext() {
    }

    public static BeagleSession current() {
        return CURRENT.get();
    }

    public static boolean isActive() {
        BeagleSession s = CURRENT.get();
        return s != null && !s.isClosed();
    }

    public static void bind(BeagleSession session) {
        CURRENT.set(session);
    }

    /**
     * Always call this when the unit of work ends, even on the exception path. On a
     * pooled thread a missed {@code clear()} is not merely a leak -- the next request to
     * land on that thread inherits the previous request's session and reports its
     * queries as its own.
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * Propagates the current session into another thread. Use when a unit of work hands
     * part of itself to an executor and the queries issued there should still count.
     */
    public static Runnable wrap(Runnable task) {
        BeagleSession session = CURRENT.get();
        if (session == null) {
            return task;
        }
        return () -> {
            BeagleSession previous = CURRENT.get();
            bind(session);
            try {
                task.run();
            } finally {
                if (previous == null) {
                    clear();
                } else {
                    bind(previous);
                }
            }
        };
    }
}
