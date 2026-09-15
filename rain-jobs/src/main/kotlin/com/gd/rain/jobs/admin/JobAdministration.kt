package com.gd.rain.jobs.admin

import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.SubjectKey
import java.time.Instant
import java.util.UUID

/**
 * Operating the queue: reading what ended badly, putting it back, and cancelling everything about a subject. Present in
 * every runtime role; nothing here needs a running scheduler.
 */
public interface JobAdministration {
    /**
     * One keyset page of failed and dead invocations, newest first by `(finished_at, id)`, optionally of one
     * definition. [limit] is 1..[MAX_PAGE]. There is no total: counting a growing set is not a page's business.
     */
    public fun deadLetters(
        definition: String?,
        after: DeadLetterCursor?,
        limit: Int,
    ): DeadLetterPage

    /**
     * Puts one failed or dead invocation back, in one transaction: its definition is resolved, its stored
     * reservation is taken again, it is requeued with a fresh budget under a new generation, and a db-scheduler
     * execution of that generation is scheduled. Joins the caller's transaction when there is one.
     */
    public fun redrive(invocation: UUID): RedriveOutcome

    /**
     * Cancels every queued or running invocation about [subject], in batches of [CANCEL_BATCH] rows per statement,
     * releasing their reservations. A running attempt is fenced out of its next effect and loses its lease at the
     * next renewal. Joins the caller's transaction when there is one.
     */
    public fun cancelBySubject(subject: SubjectKey): CancelOutcome

    /** The declared definitions, by name. */
    public fun definitions(): List<JobDefinitionView>

    public companion object {
        public const val MAX_PAGE: Int = 500
        public const val CANCEL_BATCH: Int = 500
    }
}

/** Where a dead-letter page continues: the last item of the previous page. */
public data class DeadLetterCursor(
    public val finishedAt: Instant,
    public val id: UUID,
)

public data class DeadLetter(
    public val id: UUID,
    public val definition: String,
    public val profile: String,
    public val state: JobState,
    public val attempts: Int,
    public val retrySpent: Int,
    public val retryLimit: Int,
    public val failureCode: String?,
    public val failureMessage: String?,
    public val finishedAt: Instant,
    public val subjectKey: SubjectKey?,
    public val absorbedCount: Int,
)

public data class DeadLetterPage(
    public val items: List<DeadLetter>,
    /** Null on the last page. */
    public val next: DeadLetterCursor?,
)

public sealed interface RedriveOutcome {
    /** Requeued and scheduled under [generation]. */
    public data class Redriven(
        public val invocation: UUID,
        public val generation: Int,
    ) : RedriveOutcome

    public data object NotFound : RedriveOutcome

    /** Only failed and dead invocations are redriven; [state] is where this one is. */
    public data class NotTerminal(
        public val state: JobState,
    ) : RedriveOutcome

    /** The invocation's definition is not declared in this application; nothing was written. */
    public data class UnknownDefinition(
        public val definition: String,
    ) : RedriveOutcome

    /** The invocation's reservation is held by [holder] now; nothing was written. */
    public data class Deduplicated(
        public val holder: UUID,
    ) : RedriveOutcome
}

public data class CancelOutcome(
    public val cancelled: Long,
    public val batches: Int,
)

public data class JobDefinitionView(
    public val name: String,
    public val profile: String,
    public val payloadType: String,
    public val workers: Int,
)
