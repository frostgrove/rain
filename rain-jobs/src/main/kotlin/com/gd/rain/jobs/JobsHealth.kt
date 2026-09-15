package com.gd.rain.jobs

import java.time.Instant

/**
 * What a worker process's job machinery is doing, for a health contribution to wrap. Present only in the worker
 * role, where schedulers exist.
 */
public interface JobsHealth {
    public fun report(): JobsHealthReport
}

public data class JobsHealthReport(
    /** Whether the worker lifecycle is started. */
    public val running: Boolean,
    public val schedulers: List<SchedulerHealth>,
    public val leaseRenewer: RenewerHealth,
)

public data class SchedulerHealth(
    /** A profile id, or [RECURRING] for the scheduler of recurring work. */
    public val name: String,
    public val threads: Int,
    public val started: Boolean,
    public val shuttingDown: Boolean,
    /** The last time this scheduler finished polling for due work, by the injected clock; null before its first poll. */
    public val lastPoll: Instant?,
    /** Attempts whose scheduler thread stopped waiting (timeout or shutdown) while their body still runs. */
    public val wedgedAttempts: Int,
) {
    public companion object {
        public const val RECURRING: String = "recurring"
    }
}

public data class RenewerHealth(
    public val running: Boolean,
    public val lastRound: Instant?,
    public val activeLeases: Int,
)
