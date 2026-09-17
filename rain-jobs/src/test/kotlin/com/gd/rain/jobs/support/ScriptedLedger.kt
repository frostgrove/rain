package com.gd.rain.jobs.support

import com.gd.rain.core.lock.Guard
import com.gd.rain.jobs.Attempt
import com.gd.rain.jobs.JacksonJobPayloadCodec
import com.gd.rain.jobs.JobHandler
import com.gd.rain.jobs.JobProfile
import com.gd.rain.jobs.JobState
import com.gd.rain.jobs.context.DurableJobContextProvider
import com.gd.rain.jobs.context.PartitionPermit
import com.gd.rain.jobs.internal.context.DurableJobContexts
import com.gd.rain.jobs.internal.context.StoredJobContext
import com.gd.rain.jobs.internal.execution.AttemptControl
import com.gd.rain.jobs.internal.execution.AttemptThreads
import com.gd.rain.jobs.internal.execution.DefinitionGate
import com.gd.rain.jobs.internal.execution.EffectFence
import com.gd.rain.jobs.internal.execution.ExecutionSettings
import com.gd.rain.jobs.internal.execution.JobExecution
import com.gd.rain.jobs.internal.execution.LeaseRenewer
import com.gd.rain.jobs.internal.ledger.AttemptLedger
import com.gd.rain.jobs.internal.ledger.ClaimRequest
import com.gd.rain.jobs.internal.ledger.ClaimResult
import com.gd.rain.jobs.internal.ledger.ClaimedInvocation
import com.gd.rain.jobs.internal.ledger.InvocationSnapshot
import com.gd.rain.jobs.internal.ledger.LeaseRef
import com.gd.rain.jobs.internal.ledger.RenewalAnswer
import com.gd.rain.jobs.internal.ledger.WriteResult
import com.gd.rain.test.MutableClock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** An in-memory [AttemptLedger] that answers what a test scripts and records every write it is asked for. */
internal class ScriptedLedger(
    private val retrySpent: Int = 0,
    private val retryLimit: Int = 4,
    private val context: StoredJobContext = StoredJobContext(null, null, null, null),
) : AttemptLedger {
    val writes = CopyOnWriteArrayList<String>()
    val claims = AtomicInteger()

    override fun claim(request: ClaimRequest): ClaimResult {
        claims.incrementAndGet()
        return ClaimResult.Claimed(
            ClaimedInvocation(
                request.invocation,
                DEFINITION,
                PROFILE,
                "{\"text\":\"t\"}",
                1,
                retrySpent,
                retryLimit,
                0,
                null,
                context.version,
                context.bytes,
                context.producerPartition,
                context.payloadDigest,
            ),
        )
    }

    override fun finish(
        lease: LeaseRef,
        state: JobState,
        code: String?,
        message: String?,
        now: Instant,
    ): WriteResult = record("finish ${state.wire} $code at $now")

    override fun retry(
        lease: LeaseRef,
        code: String,
        message: String,
        eligibleAt: Instant,
    ): WriteResult = record("retry $code until $eligibleAt")

    override fun defer(
        lease: LeaseRef,
        eligibleAt: Instant,
    ): WriteResult = record("defer until $eligibleAt")

    override fun release(
        lease: LeaseRef,
        eligibleAt: Instant,
    ): WriteResult = record("release due $eligibleAt")

    override fun fence(
        lease: LeaseRef,
        now: Instant,
    ): Boolean = error("no fence in a scripted attempt")

    override fun snapshot(invocation: UUID): InvocationSnapshot? = null

    override fun renew(
        leases: Collection<LeaseRef>,
        expiresAt: Instant,
    ): RenewalAnswer = RenewalAnswer(leases.map { it.invocation }.toSet(), emptySet())

    private fun record(write: String): WriteResult {
        writes += write
        return WriteResult.Written
    }

    companion object {
        const val DEFINITION = "notes.write"
        const val PROFILE = "standard"
    }
}

/** A [JobExecution] over a [ScriptedLedger], no database, the attempt threads supplied by the test. */
internal class ScriptedExecution(
    threads: AttemptThreads,
    profile: JobProfile = Fixtures.profile(),
    val ledger: ScriptedLedger = ScriptedLedger(),
    contextProviders: List<DurableJobContextProvider> = emptyList(),
    partitionPermit: PartitionPermit = PartitionPermit.NONE,
    body: (Note, Attempt) -> Unit,
) {
    val clock = MutableClock(Fixtures.START)
    val gate = DefinitionGate(mapOf(ScriptedLedger.DEFINITION to 1))
    val wedged = AtomicInteger()
    val renewer = LeaseRenewer(ledger, clock, Duration.ofSeconds(30))
    val execution =
        JobExecution(
            handler =
                object : JobHandler<Note> {
                    override val definition = Fixtures.definition()

                    override fun handle(
                        payload: Note,
                        attempt: Attempt,
                    ) = body(payload, attempt)
                },
            profile = profile,
            gate = gate,
            ledger = ledger,
            renewer = renewer,
            fences =
                object : EffectFence {
                    override fun <T> fenced(
                        control: AttemptControl,
                        guards: List<Guard>,
                        effect: () -> T,
                    ): T = error("no fence in a scripted attempt")
                },
            threads = threads,
            codec = JacksonJobPayloadCodec(),
            contexts = DurableJobContexts(contextProviders),
            partitionPermit = partitionPermit,
            settings = ExecutionSettings(Duration.ofSeconds(30), Duration.ofSeconds(2), Duration.ofSeconds(15), "scripted#standard"),
            wedged = wedged,
            jitter = { 0 },
            ids = Fixtures.ids,
            clock = clock,
        )
}
