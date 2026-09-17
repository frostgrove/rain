package com.gd.rain.jobs

import com.gd.rain.jobs.context.TenantBindingMode

/**
 * One kind of work, declared by the application as a bean.
 *
 * [name] is unique across the application, is the db-scheduler task name, the value stored in
 * `job_invocation.definition`, and the key of its worker ceiling `rain.jobs.workers.<name>`.
 * [payloadType] is the class the payload is stored as (JSON) and handed back as.
 */
public data class JobDefinition<P : Any>(
    public val name: String,
    public val profile: String,
    public val payloadType: Class<P>,
    /** Durable tenant-context policy, declared with the job rather than inferred from queue names or annotations. */
    public val tenantBinding: TenantBindingMode = TenantBindingMode.INHERIT,
) {
    init {
        require(NAME.matches(name)) { "a job definition name matches ${NAME.pattern}, got \"$name\"" }
        require(JobProfile.ID.matches(profile)) { "definition $name: a profile id matches ${JobProfile.ID.pattern}, got \"$profile\"" }
    }

    public companion object {
        public val NAME: Regex = Regex("^[a-z][a-z0-9.-]{0,127}$")

        public inline fun <reified P : Any> of(
            name: String,
            profile: String,
            tenantBinding: TenantBindingMode = TenantBindingMode.INHERIT,
        ): JobDefinition<P> = JobDefinition(name, profile, P::class.java, tenantBinding)
    }
}

/** Execution order among due work: a higher value runs first (db-scheduler's native order). */
public data class JobPriority(
    public val value: Int,
) {
    init {
        require(value in Short.MIN_VALUE..Short.MAX_VALUE) { "a job priority fits a SMALLINT, got $value" }
    }
}

/** What an invocation is about, so every live invocation about it can be cancelled together. */
public data class SubjectKey(
    public val value: String,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_LENGTH) { "a subject key is 1..$MAX_LENGTH non-blank characters" }
    }

    public companion object {
        public const val MAX_LENGTH: Int = 256
    }
}

/** Where an invocation is; [wire] is the stored value. */
public enum class JobState(
    public val wire: String,
    public val terminal: Boolean,
) {
    QUEUED("queued", false),
    RUNNING("running", false),
    SUCCEEDED("succeeded", true),

    /** A permanent refusal: no budget was spent. */
    FAILED("failed", true),

    /** A budget ran out, or the definition is not known to the reaper that found it. */
    DEAD("dead", true),
    CANCELLED("cancelled", true),
    ;

    public companion object {
        public fun fromWire(wire: String): JobState =
            entries.firstOrNull { it.wire == wire } ?: throw IllegalStateException("\"$wire\" is not a job state")

        public val DEAD_LETTERS: Set<JobState> = setOf(FAILED, DEAD)
        public val LIVE: Set<JobState> = setOf(QUEUED, RUNNING)
    }
}

/** Why an attempt or an invocation ended, as `job_invocation.failure_code` records it. */
public object FailureCode {
    public const val FAILED: String = "failed"
    public const val PERMANENT: String = "permanent"
    public const val ATTEMPT_TIMEOUT: String = "attempt_timeout"
    public const val DEFERRALS_EXHAUSTED: String = "deferrals_exhausted"
    public const val LEASE_EXPIRED: String = "lease_expired"
    public const val UNKNOWN_DEFINITION: String = "unknown_definition"
    public const val STATEMENT_BOUND_FAILED: String = "statement_bound_failed"
    public const val CONTEXT_INVALID: String = "context_invalid"
    public const val CONTEXT_UNAVAILABLE: String = "context_unavailable"

    /** The stored payload cannot be read as the definition's payload type; retrying cannot change that. */
    public const val PAYLOAD_UNREADABLE: String = "payload_unreadable"
}
