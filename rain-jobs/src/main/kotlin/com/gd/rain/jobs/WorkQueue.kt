package com.gd.rain.jobs

import java.time.Duration
import java.util.UUID

/**
 * How an order is deduplicated against other orders for the same definition and key.
 *
 * - [Unique] is held until the invocation reaches a terminal state: a second order while the first is queued
 *   or running is absorbed into it.
 * - [Collapse] is released when a worker claims the invocation, before the handler reads anything: it absorbs
 *   only orders made while the job had not started looking.
 */
public sealed interface Dedupe {
    public data object None : Dedupe

    public data class Unique(
        public val key: String,
    ) : Dedupe {
        init {
            requireKey(key)
        }
    }

    public data class Collapse(
        public val key: String,
    ) : Dedupe {
        init {
            requireKey(key)
        }
    }

    public companion object {
        public const val MAX_KEY_LENGTH: Int = 512

        private fun requireKey(key: String) {
            require(key.isNotBlank() && key.length <= MAX_KEY_LENGTH) { "a dedupe key is 1..$MAX_KEY_LENGTH non-blank characters" }
        }
    }
}

/**
 * What an order asks for beyond its payload. [dedupe] and [priority] decide behaviour and are always stated;
 * [after] delays eligibility and [subjectKey] makes the order cancellable with every other order about the same
 * subject.
 */
public data class EnqueueOptions(
    public val dedupe: Dedupe,
    public val priority: JobPriority,
    public val after: Duration = Duration.ZERO,
    public val subjectKey: SubjectKey? = null,
) {
    init {
        require(!after.isNegative) { "an enqueue delay is not negative, got $after" }
    }
}

/** What an enqueue answers: the invocation that will run the work. */
public sealed interface EnqueueOutcome {
    public val invocation: UUID

    /** This order created [invocation] and scheduled it. */
    public data class Scheduled(
        override val invocation: UUID,
    ) : EnqueueOutcome

    /** A reservation held by [invocation] absorbed this order; nothing was written for it but the absorption count. */
    public data class Deduplicated(
        override val invocation: UUID,
    ) : EnqueueOutcome
}

/**
 * Putting work on the queue. An enqueue joins the caller's transaction when there is one — the order, its
 * reservation and its db-scheduler execution commit or roll back with the caller's change — and otherwise
 * commits on its own. It needs no running scheduler, so every runtime role may enqueue.
 */
public interface WorkQueue {
    public fun <P : Any> enqueue(
        definition: JobDefinition<P>,
        payload: P,
        options: EnqueueOptions,
    ): EnqueueOutcome
}

/** The definition was not declared as a bean in this application, or a different definition carries its name. */
public class UnknownJobDefinitionException(
    public val definition: String,
) : IllegalArgumentException("job definition \"$definition\" is not declared in this application")

/**
 * A reservation was taken and released so many times in a row that the order could not be placed against a
 * stable holder. The order is refused rather than guessed; repeating it is safe.
 */
public class IntentConflictException(
    public val definition: String,
    public val key: String,
    public val placements: Int,
) : IllegalStateException("the reservation $definition/$key changed holder during each of $placements placements; the order is refused")
