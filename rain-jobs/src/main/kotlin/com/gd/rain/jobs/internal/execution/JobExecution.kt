package com.gd.rain.jobs.internal.execution

import com.gd.rain.core.id.IdGenerator
import com.gd.rain.jobs.AttemptInterruptedException
import com.gd.rain.jobs.AttemptMeta
import com.gd.rain.jobs.AttemptTimeoutException
import com.gd.rain.jobs.FailureCode
import com.gd.rain.jobs.Jitter
import com.gd.rain.jobs.JobDeferredException
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobPayloadCodec
import com.gd.rain.jobs.JobPermanentException
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.LeaseLostException
import com.gd.rain.jobs.SubjectKey
import com.gd.rain.jobs.internal.JobTaskData
import com.gd.rain.jobs.internal.ledger.AttemptLedger
import com.gd.rain.jobs.internal.ledger.ClaimRequest
import com.gd.rain.jobs.internal.ledger.ClaimResult
import com.gd.rain.jobs.internal.ledger.ClaimedInvocation
import com.gd.rain.jobs.internal.ledger.LeaseRef
import com.gd.rain.jobs.internal.ledger.WriteResult
import com.github.kagkarlsson.scheduler.task.CompletionHandler
import com.github.kagkarlsson.scheduler.task.ExecutionContext
import com.github.kagkarlsson.scheduler.task.ExecutionHandler
import com.github.kagkarlsson.scheduler.task.TaskInstance
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/** The per-process numbers an execution needs. */
internal data class ExecutionSettings(
    val leaseTtl: Duration,
    val pollInterval: Duration,
    val reaperInterval: Duration,
    val pickedBy: String,
)

/** How an attempt ended, before anything is written. */
internal sealed interface AttemptOutcome {
    data object Succeeded : AttemptOutcome

    data class Permanent(
        val code: String,
        val message: String,
    ) : AttemptOutcome

    data class Charged(
        val code: String,
        val message: String,
    ) : AttemptOutcome

    data class Deferred(
        val after: Duration,
    ) : AttemptOutcome

    /** This attempt does not own the invocation any more: nothing is written. */
    data object LeaseLost : AttemptOutcome

    /** The process is stopping: an uncharged release, due again at once. */
    data object Interrupted : AttemptOutcome
}

/**
 * One definition's db-scheduler execution handler. It parks on nothing: a definition at its ceiling is rescheduled,
 * the body runs on a thread of its own, and the only wait is a bounded one for that body.
 */
internal class JobExecution(
    private val handler: JobHandler<*>,
    private val profile: JobProfile,
    private val gate: DefinitionGate,
    private val ledger: AttemptLedger,
    private val renewer: LeaseRenewer,
    private val fences: EffectFence,
    private val threads: AttemptThreads,
    private val codec: JobPayloadCodec,
    private val settings: ExecutionSettings,
    private val wedged: AtomicInteger,
    private val jitter: Jitter,
    private val ids: IdGenerator,
    private val clock: Clock,
) : ExecutionHandler<JobTaskData> {
    private val definition = handler.definition

    override fun execute(
        taskInstance: TaskInstance<JobTaskData>,
        executionContext: ExecutionContext?,
    ): CompletionHandler<JobTaskData> = attempt(taskInstance.data).handler()

    fun attempt(data: JobTaskData): Completion {
        if (!gate.tryEnter(definition.name)) return Completion.Reschedule(clock.instant().plus(settings.pollInterval))
        var gateHandedOver = false
        try {
            val now = clock.instant()
            val lease = LeaseRef(data.invocation, ids.next())
            val claim =
                ledger.claim(
                    ClaimRequest(data.invocation, data.generation, lease.token, settings.pickedBy, now, now.plus(settings.leaseTtl)),
                )
            return when (claim) {
                is ClaimResult.NotClaimed -> {
                    Completion.afterRefusal(claim.current, data.generation, now, settings.reaperInterval)
                }

                is ClaimResult.Claimed -> {
                    run(claim.invocation, lease, data.generation, now) { gateHandedOver = true }
                }
            }
        } finally {
            if (!gateHandedOver) gate.leave(definition.name)
        }
    }

    private fun run(
        claimed: ClaimedInvocation,
        lease: LeaseRef,
        generation: Int,
        claimedAt: Instant,
        handedOver: () -> Unit,
    ): Completion {
        val deadline = claimedAt.plus(profile.attemptTimeout)
        val control = AttemptControl(lease, profile, deadline, wedged)
        val meta =
            AttemptMeta(
                invocation = claimed.id,
                definition = definition.name,
                profile = profile.id,
                attempt = claimed.attempts,
                retrySpent = claimed.retrySpent,
                retryLimit = claimed.retryLimit,
                deferrals = claimed.deferrals,
                deadline = deadline,
                subjectKey = claimed.subjectKey?.let(::SubjectKey),
            )
        renewer.register(control, claimedAt)
        val handle =
            try {
                threads.start(
                    "rain-job-${definition.name}-${claimed.id}",
                ) { body(control, LeasedAttempt(meta, control, fences, clock), claimed) }
            } catch (failure: Throwable) {
                renewer.unregister(control)
                throw failure
            }
        handedOver()
        val waited = handle.await(Duration.between(clock.instant(), deadline))
        val interrupted = waited == AwaitResult.INTERRUPTED
        if (waited != AwaitResult.FINISHED) {
            control.revoke(if (interrupted) Revocation.SHUTDOWN else Revocation.TIMEOUT)
            control.abandon()
        }
        try {
            return complete(classify(control), claimed, lease, generation)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun body(
        control: AttemptControl,
        attempt: LeasedAttempt,
        claimed: ClaimedInvocation,
    ) {
        control.bind(Thread.currentThread())
        try {
            control.checkRunnable(clock)
            val payload =
                try {
                    codec.decode(claimed.payloadJson, definition.payloadType)
                } catch (failure: RuntimeException) {
                    control.finish(BodyResult.PayloadUnreadable(failure))
                    return
                }
            AttemptScope.within(control) { handle(payload, attempt) }
            control.finish(BodyResult.Returned)
        } catch (failure: Throwable) {
            control.finish(BodyResult.Threw(failure))
        } finally {
            renewer.unregister(control)
            gate.leave(definition.name)
            control.exited()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handle(
        payload: Any,
        attempt: LeasedAttempt,
    ) = (handler as JobHandler<Any>).handle(payload, attempt)

    private fun classify(control: AttemptControl): AttemptOutcome {
        when (control.revocation) {
            Revocation.LEASE_LOST -> return AttemptOutcome.LeaseLost

            Revocation.SHUTDOWN -> return AttemptOutcome.Interrupted

            Revocation.TIMEOUT -> return AttemptOutcome.Charged(
                FailureCode.ATTEMPT_TIMEOUT,
                "the attempt did not finish within ${profile.attemptTimeout}",
            )

            null -> Unit
        }
        control.boundFailure?.let { return AttemptOutcome.Charged(FailureCode.STATEMENT_BOUND_FAILED, describe(it)) }
        return when (val result = checkNotNull(control.result) { "the attempt body finished without recording a result" }) {
            BodyResult.Returned -> AttemptOutcome.Succeeded
            is BodyResult.PayloadUnreadable -> AttemptOutcome.Permanent(FailureCode.PAYLOAD_UNREADABLE, describe(result.failure))
            is BodyResult.Threw -> classifyFailure(result.failure)
        }
    }

    private fun classifyFailure(failure: Throwable): AttemptOutcome =
        when (failure) {
            is JobDeferredException -> AttemptOutcome.Deferred(failure.after)
            is JobPermanentException -> AttemptOutcome.Permanent(FailureCode.PERMANENT, describe(failure))
            is LeaseLostException -> AttemptOutcome.LeaseLost
            is AttemptInterruptedException -> AttemptOutcome.Interrupted
            is AttemptTimeoutException -> AttemptOutcome.Charged(FailureCode.ATTEMPT_TIMEOUT, describe(failure))
            else -> AttemptOutcome.Charged(FailureCode.FAILED, describe(failure))
        }

    private fun complete(
        outcome: AttemptOutcome,
        claimed: ClaimedInvocation,
        lease: LeaseRef,
        generation: Int,
    ): Completion {
        val now = clock.instant()

        fun refused(write: WriteResult): Completion? =
            (write as? WriteResult.NotOwner)?.let { Completion.afterRefusal(it.current, generation, now, settings.reaperInterval) }

        return when (outcome) {
            AttemptOutcome.Succeeded -> {
                refused(ledger.finish(lease, JobState.SUCCEEDED, null, null, now)) ?: Completion.Remove
            }

            is AttemptOutcome.Permanent -> {
                refused(ledger.finish(lease, JobState.FAILED, outcome.code, outcome.message, now)) ?: Completion.Remove
            }

            is AttemptOutcome.Charged -> {
                if (claimed.retrySpent >= claimed.retryLimit) {
                    log.warn(
                        "{} invocation {} is dead after {} attempts: {}",
                        definition.name,
                        claimed.id,
                        claimed.attempts,
                        outcome.message,
                    )
                    refused(ledger.finish(lease, JobState.DEAD, outcome.code, outcome.message, now)) ?: Completion.Remove
                } else {
                    val eligibleAt = now.plus(profile.backoff.delay(claimed.retrySpent, jitter))
                    refused(ledger.retry(lease, outcome.code, outcome.message, eligibleAt)) ?: Completion.Reschedule(eligibleAt)
                }
            }

            is AttemptOutcome.Deferred -> {
                if (claimed.deferrals >= profile.deferrals) {
                    val message = "deferred ${claimed.deferrals} times, the profile's budget"
                    refused(ledger.finish(lease, JobState.DEAD, FailureCode.DEFERRALS_EXHAUSTED, message, now)) ?: Completion.Remove
                } else {
                    val eligibleAt = now.plus(outcome.after)
                    refused(ledger.defer(lease, eligibleAt)) ?: Completion.Reschedule(eligibleAt)
                }
            }

            AttemptOutcome.Interrupted -> {
                refused(ledger.release(lease, now)) ?: Completion.Reschedule(now)
            }

            AttemptOutcome.LeaseLost -> {
                Completion.afterRefusal(ledger.snapshot(lease.invocation), generation, now, settings.reaperInterval)
            }
        }
    }

    private fun describe(failure: Throwable): String = failure.message?.let { "${failure.javaClass.name}: $it" } ?: failure.javaClass.name

    private companion object {
        val log = LoggerFactory.getLogger(JobExecution::class.java)
    }
}
