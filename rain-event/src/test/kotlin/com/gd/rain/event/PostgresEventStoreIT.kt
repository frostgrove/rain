package com.gd.rain.event

import com.gd.rain.event.postgres.PostgresEventStore
import com.gd.rain.event.projection.ProjectionAdvancement
import com.gd.rain.event.projection.ProjectionCheckpointContract
import com.gd.rain.event.projection.ProjectionClaim
import com.gd.rain.event.projection.ProjectionContractRevision
import com.gd.rain.event.projection.ProjectionCover
import com.gd.rain.event.projection.ProjectionDestinationId
import com.gd.rain.event.projection.ProjectionEffectAdmission
import com.gd.rain.event.projection.ProjectionEffectPolicy
import com.gd.rain.event.projection.ProjectionFact
import com.gd.rain.event.projection.ProjectionGeneration
import com.gd.rain.event.projection.ProjectionGenerationPlan
import com.gd.rain.event.projection.ProjectionGenerationReadiness
import com.gd.rain.event.projection.ProjectionGenerationRegistration
import com.gd.rain.event.projection.ProjectionHoldEnqueue
import com.gd.rain.event.projection.ProjectionHoldLimits
import com.gd.rain.event.projection.ProjectionLane
import com.gd.rain.event.projection.ProjectionLetter
import com.gd.rain.event.projection.ProjectionName
import com.gd.rain.event.projection.ProjectionPartition
import com.gd.rain.event.projection.ProjectionPermanentFailurePolicy
import com.gd.rain.event.projection.ProjectionRoute
import com.gd.rain.event.projection.ProjectionRunBudget
import com.gd.rain.event.projection.ProjectionSpec
import com.gd.rain.event.projection.ProjectionTopologyFingerprint
import com.gd.rain.event.projection.SameUnitProjectionDefinition
import com.gd.rain.event.projection.SameUnitProjectionRunner
import com.gd.rain.event.projection.SequenceKeyHasher
import com.gd.rain.event.projection.SequenceKeyHasherId
import com.gd.rain.event.projection.postgres.PostgresProjectionCheckpointStore
import com.gd.rain.event.projection.postgres.PostgresProjectionGenerationStore
import com.gd.rain.event.projection.postgres.PostgresProjectionHoldStore
import com.gd.rain.event.projection.postgres.PostgresSameUnitProjectionDestination
import com.gd.rain.persistence.schema.RainSchemaMigrationStrategy
import com.gd.rain.persistence.schema.SchemaDescriptor
import com.gd.rain.test.RainPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Tag("integration")
class PostgresEventStoreIT {
    @Test
    fun `one statement appends a dense batch and confirms a stale expected version`() {
        val database = RainPostgres.freshDatabase("event_append")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val store = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 1 }), "counter", "one")
        val changes =
            EncodedChanges(
                stream,
                listOf(
                    EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("1")),
                    EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("2")),
                ),
            )
        val metadata = EventMetadata(OperationKey.of("operation-one"))
        val template = TransactionTemplate(transactions)

        val committed =
            template.execute {
                store.inCallerTransaction { transaction -> store.append(transaction, 0, changes, metadata) }
            }
        val conflict =
            template.execute {
                store.inCallerTransaction { transaction ->
                    store.append(transaction, 0, changes, EventMetadata(OperationKey.of("operation-two")))
                }
            }

        assertThat(committed).isEqualTo(AppendResult.Committed(CommitRange(1, 2, 1, 2, 2)))
        assertThat(conflict).isEqualTo(AppendResult.Conflict)
    }

    @Test
    fun `receipt claim runs a decision once and returns its original result on repetition`() {
        val database = RainPostgres.freshDatabase("event_receipt")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val store = PostgresEventStore(dsl, transactions)
        val namespace = EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 2 })
        val stream = StreamRef(namespace, "counter", "receipt")
        val changes = EncodedChanges(stream, listOf(EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("1"))))
        val operation = OperationKey.of("operation-receipt")
        val request = RequestFingerprint.of(EventBytes.utf8("command-v1"))
        val template = TransactionTemplate(transactions)

        val first =
            template.execute {
                store.inCallerTransaction { transaction ->
                    val claimed = store.claim(transaction, namespace, operation, request) as ReceiptClaim.Claimed
                    val metadata = EventMetadata(operation)
                    val append = store.append(transaction, 0, changes, metadata) as AppendResult.Committed
                    store.complete(
                        transaction,
                        claimed.token,
                        ReceiptCompletion(
                            stream,
                            append.range,
                            appendFingerprint(stream, changes.facts, metadata),
                            metadata,
                            ReceiptResponse("counter.reply", 1, EventBytes.utf8("ok")),
                        ),
                    )
                }
            }
        val repeated =
            template.execute {
                store.inCallerTransaction { transaction -> store.claim(transaction, namespace, operation, request) }
            }

        assertThat(first.range).isEqualTo(CommitRange(1, 1, 1, 1, 1))
        assertThat(repeated).isEqualTo(ReceiptClaim.Repeated(first))
        assertThatThrownBy {
            template.execute {
                store.inCallerTransaction { transaction ->
                    store.claim(transaction, namespace, operation, RequestFingerprint.of(EventBytes.utf8("command-v2")))
                }
            }
        }.isInstanceOf(ReceiptRefusal.Collision::class.java)
    }

    @Test
    fun `snapshots are transaction-bound idempotent records and bounded pruning never touches source events`() {
        val database = RainPostgres.freshDatabase("event_snapshot")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val store = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 3 }), "counter", "snapshot")
        val changes =
            EncodedChanges(
                stream,
                listOf(
                    EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("1")),
                    EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8("2")),
                ),
            )
        val fingerprint = SnapshotFingerprint.of(ByteArray(32) { 4 })
        val codec = SnapshotCodecId("counter.state", 1)
        val template = TransactionTemplate(transactions)

        val result =
            template.execute {
                store.inCallerTransaction { transaction ->
                    assertThat(store.append(transaction, 0, changes, EventMetadata(OperationKey.of("snapshot-write"))))
                        .isEqualTo(AppendResult.Committed(CommitRange(1, 2, 1, 2, 2)))
                    val first = SnapshotWrite(stream, 1, fingerprint, codec, EventBytes.utf8("1"))
                    val second = SnapshotWrite(stream, 2, fingerprint, codec, EventBytes.utf8("3"))
                    assertThat(store.save(transaction, first)).isInstanceOf(SnapshotSaveResult.Saved::class.java)
                    assertThat(store.save(transaction, first)).isInstanceOf(SnapshotSaveResult.Repeated::class.java)
                    assertThat(
                        store.save(
                            transaction,
                            SnapshotWrite(stream, 1, fingerprint, SnapshotCodecId("counter.other", 1), EventBytes.utf8("1")),
                        ),
                    ).isEqualTo(SnapshotSaveResult.Collision)
                    assertThat(store.save(transaction, second)).isInstanceOf(SnapshotSaveResult.Saved::class.java)
                    assertThat(store.latest(transaction, stream, 2)?.version).isEqualTo(2)
                    assertThat(store.prune(transaction, stream, keep = 1, batch = 1)).isEqualTo(1)
                    assertThat(store.version(transaction, stream)).isEqualTo(2)
                    store.latest(transaction, stream, 2)
                }
            }

        assertThat(result?.version).isEqualTo(2)
    }

    @Test
    fun `committed log never skips a lower identity position held by an older transaction`() {
        val database = RainPostgres.freshDatabase("event_committed_log")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val store = PostgresEventStore(dsl, transactions)
        val namespace = EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 4 })
        val low = StreamRef(namespace, "counter", "low")
        val high = StreamRef(namespace, "counter", "high")
        val lowWritten = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val held =
                executor.submit {
                    TransactionTemplate(transactions).execute {
                        store.inCallerTransaction { transaction ->
                            assertThat(
                                store.append(
                                    transaction,
                                    0,
                                    encoded(low, "1"),
                                    EventMetadata(OperationKey.of("low")),
                                ),
                            ).isInstanceOf(AppendResult.Committed::class.java)
                            lowWritten.countDown()
                            check(allowCommit.await(10, TimeUnit.SECONDS)) { "test did not release held event transaction" }
                        }
                    }
                }
            assertThat(lowWritten.await(10, TimeUnit.SECONDS)).isTrue()

            TransactionTemplate(transactions).execute {
                store.inCallerTransaction { transaction ->
                    assertThat(
                        store.append(transaction, 0, encoded(high, "2"), EventMetadata(OperationKey.of("high"))),
                    ).isInstanceOf(AppendResult.Committed::class.java)
                }
            }

            val stalled = store.readCommitted(store.initialCursor(), 10)
            assertThat(stalled.events).isEmpty()
            assertThat(stalled.hasMore).isFalse()

            allowCommit.countDown()
            held.get(10, TimeUnit.SECONDS)

            val settled = store.readCommitted(stalled.next, 10)
            assertThat(settled.events.map { it.position }).containsExactly(1, 2)
            assertThat(settled.events.map { it.stream }).containsExactly(low, high)
            assertThat(store.mark(CommitRange(1, 2, 1, 2, 2))).isEqualTo(EventLogMark(store.origin, 2))
        } finally {
            allowCommit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `projection checkpoint leases fence stale writers and reject contract drift`() {
        val database = RainPostgres.freshDatabase("projection_checkpoint")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val events = PostgresEventStore(dsl, transactions)
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin)
        val lane = ProjectionLane(ProjectionName.of("counter-view"), ProjectionGeneration(1), ProjectionPartition.WHOLE)
        val initial = events.initialCursor()
        val contract =
            ProjectionCheckpointContract(
                events.origin,
                ProjectionContractRevision(1),
                ProjectionTopologyFingerprint.of(ByteArray(32) { 9 }),
            )
        val now = Instant.parse("2026-09-17T00:00:00Z")

        val claimed = checkpoints.claim(lane, contract, initial, now, java.time.Duration.ofMinutes(1)) as ProjectionClaim.Acquired
        assertThat(
            checkpoints.claim(lane, contract, initial, now, java.time.Duration.ofMinutes(1)),
        ).isInstanceOf(ProjectionClaim.Busy::class.java)
        assertThat(checkpoints.advance(claimed.lease, EventLogCursor.after(initial, 1), now))
            .isInstanceOf(com.gd.rain.event.projection.ProjectionAdvance.Advanced::class.java)
        assertThat(checkpoints.advance(claimed.lease, EventLogCursor.after(initial, 2), now))
            .isEqualTo(com.gd.rain.event.projection.ProjectionAdvance.LostLease)
        assertThat(checkpoints.checkpoint(lane)?.cursor?.deliveredPosition).isEqualTo(1)

        val changed = contract.copy(revision = ProjectionContractRevision(2))
        assertThat(checkpoints.claim(lane, changed, initial, now, java.time.Duration.ofMinutes(1)))
            .isEqualTo(ProjectionClaim.ContractDrift)
    }

    @Test
    fun `committed log cursor survives a fresh datasource identity for the same durable schema log`() {
        val database = RainPostgres.freshDatabase("event_log_restart")
        val firstDataSource = database.dataSource()
        val firstTransactions = DataSourceTransactionManager(firstDataSource)
        val firstDsl = DSL.using(TransactionAwareDataSourceProxy(firstDataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(firstDataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val first = PostgresEventStore(firstDsl, firstTransactions)
        val cursor = first.initialCursor()

        val restartedDataSource = database.dataSource()
        val restarted =
            PostgresEventStore(
                DSL.using(TransactionAwareDataSourceProxy(restartedDataSource), SQLDialect.POSTGRES),
                DataSourceTransactionManager(restartedDataSource),
            )

        assertThat(restarted.backing).isNotEqualTo(first.backing)
        assertThat(restarted.origin).isEqualTo(first.origin)
        assertThat(restarted.readCommitted(cursor, 1).events).isEmpty()
    }

    @Test
    fun `same unit projection atomically commits handler writes with its checkpoint and rolls both back on failure`() {
        val database = RainPostgres.freshDatabase("same_unit_projection")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        dsl.execute("CREATE TABLE projection_same_unit_test (position bigint PRIMARY KEY)")
        val events = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 5 }), "counter", "one")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                assertThat(events.append(transaction, 0, encoded(stream, "1"), EventMetadata(OperationKey.of("same-unit-source"))))
                    .isInstanceOf(AppendResult.Committed::class.java)
            }
        }
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin, transactions)
        val lane = ProjectionLane(ProjectionName.of("counter-view"), ProjectionGeneration(1), ProjectionPartition.WHOLE)
        var attempts = 0
        val failing =
            SameUnitProjectionRunner(
                SameUnitProjectionDefinition(sameUnitSpec()) { batch, _ ->
                    attempts++
                    dsl.execute("INSERT INTO projection_same_unit_test(position) VALUES (?)", batch.events.single().position)
                    error("destination write rejected")
                },
                events,
                checkpoints,
                PostgresSameUnitProjectionDestination(dsl, transactions),
                java.time.Clock.systemUTC(),
                java.time.Duration.ofMinutes(1),
            )

        assertThatThrownBy {
            TransactionTemplate(transactions).execute { failing.run(lane) }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("destination write rejected")
        assertThat(dsl.fetchCount(DSL.table("projection_same_unit_test"))).isZero()
        assertThat(checkpoints.checkpoint(lane)).isNull()

        val succeeding =
            SameUnitProjectionRunner(
                SameUnitProjectionDefinition(sameUnitSpec()) { batch, _ ->
                    attempts++
                    dsl.execute("INSERT INTO projection_same_unit_test(position) VALUES (?)", batch.events.single().position)
                },
                events,
                checkpoints,
                PostgresSameUnitProjectionDestination(dsl, transactions),
                java.time.Clock.systemUTC(),
                java.time.Duration.ofMinutes(1),
            )
        val pass = TransactionTemplate(transactions).execute { succeeding.run(lane) }

        assertThat(pass).isInstanceOf(com.gd.rain.event.projection.SameUnitPass.Advanced::class.java)
        assertThat(attempts).isEqualTo(2)
        assertThat(dsl.fetchCount(DSL.table("projection_same_unit_test"))).isEqualTo(1)
        assertThat(checkpoints.checkpoint(lane)?.cursor?.deliveredPosition).isEqualTo(1)
    }

    @Test
    fun `committed log advances over a permanent identity gap left by a rolled back append`() {
        val database = RainPostgres.freshDatabase("event_log_rollback")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val store = PostgresEventStore(dsl, transactions)
        val namespace = EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 6 })

        assertThatThrownBy {
            TransactionTemplate(transactions).execute {
                store.inCallerTransaction { transaction ->
                    store.append(
                        transaction,
                        0,
                        encoded(StreamRef(namespace, "counter", "rolled-back"), "1"),
                        EventMetadata(OperationKey.of("rolled-back")),
                    )
                    error("rollback append")
                }
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("rollback append")
        TransactionTemplate(transactions).execute {
            store.inCallerTransaction { transaction ->
                assertThat(
                    store.append(
                        transaction,
                        0,
                        encoded(StreamRef(namespace, "counter", "committed"), "2"),
                        EventMetadata(OperationKey.of("committed")),
                    ),
                ).isInstanceOf(AppendResult.Committed::class.java)
            }
        }

        val page = store.readCommitted(store.initialCursor(), 10)

        assertThat(page.events.map { it.position }).containsExactly(2)
        assertThat(page.next.deliveredPosition).isEqualTo(2)
    }

    @Test
    fun `projection parking queues one causal sequence, fences redrive, and persists an operator hole`() {
        val database = RainPostgres.freshDatabase("projection_hold")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val events = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 8 }), "counter", "parked")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                assertThat(events.append(transaction, 0, encoded(stream, "1"), EventMetadata(OperationKey.of("park-one"))))
                    .isInstanceOf(AppendResult.Committed::class.java)
                assertThat(events.append(transaction, 1, encoded(stream, "2"), EventMetadata(OperationKey.of("park-two"))))
                    .isInstanceOf(AppendResult.Committed::class.java)
            }
        }
        val spec = sameUnitSpec()
        val lane = ProjectionLane(spec.name, ProjectionGeneration(1), ProjectionPartition.WHOLE)
        val contract = ProjectionCheckpointContract(events.origin, spec.revision, spec.cover.fingerprint)
        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin, transactions)
        val holds = PostgresProjectionHoldStore(dsl, events.origin, transactions)
        val limits = ProjectionHoldLimits(maxLetters = 10, maxBytes = 64 * 1024)
        val page = events.readCommitted(events.initialCursor(), 10)
        val first = page.events[0]
        val second = page.events[1]
        val sequence = spec.cover.hasher.sequence(first)

        TransactionTemplate(transactions).execute {
            val claim = checkpoints.claim(lane, contract, events.initialCursor(), Instant.now(), java.time.Duration.ofMinutes(1)) as ProjectionClaim.Acquired
            assertThat(
                holds.enqueue(
                    claim.lease,
                    ProjectionLetter(sequence, first),
                    com.gd.rain.event.projection.ProjectionFailureCode.of("destination.invalid"),
                    limits,
                ),
            ).isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
            assertThat(checkpoints.advance(claim.lease, EventLogCursor.after(events.initialCursor(), first.position), Instant.now()))
                .isInstanceOf(com.gd.rain.event.projection.ProjectionAdvance.Advanced::class.java)
        }

        TransactionTemplate(transactions).execute {
            val claim = checkpoints.claim(lane, contract, EventLogCursor.after(events.initialCursor(), first.position), Instant.now(), java.time.Duration.ofMinutes(1)) as ProjectionClaim.Acquired
            assertThat(holds.enqueue(claim.lease, ProjectionLetter(sequence, second), null, limits))
                .isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
            assertThat(checkpoints.advance(claim.lease, page.next, Instant.now()))
                .isInstanceOf(com.gd.rain.event.projection.ProjectionAdvance.Advanced::class.java)
        }

        assertThat(holds.holds(lane, 10).single().letterCount).isEqualTo(2)
        TransactionTemplate(transactions).execute {
            val claim = holds.claimRedrive(lane, Instant.now(), java.time.Duration.ofMinutes(1)) as com.gd.rain.event.projection.ProjectionRedriveClaim.Acquired
            assertThat(holds.letters(claim.lease, 10).map { it.letter.event.position }).containsExactly(first.position, second.position)
            assertThat(holds.acknowledge(claim.lease, first.position))
                .isEqualTo(com.gd.rain.event.projection.ProjectionRedriveAcknowledge.Advanced(1))
            assertThat(holds.acknowledge(claim.lease, second.position))
                .isEqualTo(com.gd.rain.event.projection.ProjectionRedriveAcknowledge.Advanced(0))
        }
        assertThat(holds.holds(lane, 10)).isEmpty()

        TransactionTemplate(transactions).execute {
            val claim = checkpoints.claim(lane, contract, page.next, Instant.now(), java.time.Duration.ofMinutes(1)) as ProjectionClaim.Acquired
            assertThat(
                holds.enqueue(
                    claim.lease,
                    ProjectionLetter(sequence, second),
                    com.gd.rain.event.projection.ProjectionFailureCode.of("destination.invalid"),
                    limits,
                ),
            ).isInstanceOf(ProjectionHoldEnqueue.Queued::class.java)
            assertThat(checkpoints.advance(claim.lease, page.next, Instant.now()))
                .isInstanceOf(com.gd.rain.event.projection.ProjectionAdvance.Advanced::class.java)
        }
        val eviction =
            TransactionTemplate(transactions).execute {
                holds.evict(
                    lane,
                    sequence,
                    com.gd.rain.event.projection.ProjectionOperator("operator", "projection-admin"),
                    "discard invalid historical envelope",
                )
            } as com.gd.rain.event.projection.ProjectionHoldEviction.Evicted
        assertThat(holds.acknowledgeHole(eviction.hole.id, com.gd.rain.event.projection.ProjectionOperator("operator", "projection-admin"), "rebuild policy accepted"))
            .isInstanceOf(com.gd.rain.event.projection.ProjectionHoleAcknowledgement.Acknowledged::class.java)
    }

    @Test
    fun `same-unit park rolls back only the failed envelope, queues its sequence, and advances unrelated work`() {
        val database = RainPostgres.freshDatabase("same_unit_park")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        dsl.execute("CREATE TABLE projection_park_test (position bigint PRIMARY KEY)")
        val events = PostgresEventStore(dsl, transactions)
        val namespace = EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 9 })
        val bad = StreamRef(namespace, "counter", "bad")
        val good = StreamRef(namespace, "counter", "good")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                events.append(transaction, 0, encoded(bad, "one"), EventMetadata(OperationKey.of("park-bad-one")))
                events.append(transaction, 0, encoded(good, "two"), EventMetadata(OperationKey.of("park-good")))
                events.append(transaction, 1, encoded(bad, "three"), EventMetadata(OperationKey.of("park-bad-two")))
            }
        }
        val spec = parkSpec()
        val lane = ProjectionLane(spec.name, ProjectionGeneration(1), ProjectionPartition.WHOLE)
        val holds = PostgresProjectionHoldStore(dsl, events.origin, transactions)
        val runner =
            SameUnitProjectionRunner(
                SameUnitProjectionDefinition.parked(
                    spec,
                    { event, _ ->
                        dsl.execute("INSERT INTO projection_park_test(position) VALUES (?)", event.position)
                        if (event.stream.key == "bad") error("declared permanent destination refusal")
                    },
                    com.gd.rain.event.projection.ProjectionFailureClassifier {
                        com.gd.rain.event.projection.ProjectionFailure.Permanent(
                            com.gd.rain.event.projection.ProjectionFailureCode.of("destination.invalid"),
                        )
                    },
                    ProjectionHoldLimits(maxLetters = 10, maxBytes = 64 * 1024),
                ),
                events,
                PostgresProjectionCheckpointStore(dsl, events.origin, transactions),
                PostgresSameUnitProjectionDestination(dsl, transactions),
                java.time.Clock.systemUTC(),
                java.time.Duration.ofMinutes(1),
                holds,
            )

        val pass = TransactionTemplate(transactions).execute { runner.run(lane) }

        assertThat(pass).isInstanceOf(com.gd.rain.event.projection.SameUnitPass.Parked::class.java)
        assertThat(dsl.fetch("SELECT position FROM projection_park_test ORDER BY position").getValues(0, Long::class.java)).containsExactly(2L)
        assertThat(holds.holds(lane, 10)).hasSize(1)
        val claimed =
            TransactionTemplate(transactions).execute {
                holds.claimRedrive(lane, Instant.now(), java.time.Duration.ofMinutes(1)) as com.gd.rain.event.projection.ProjectionRedriveClaim.Acquired
            }
        assertThat(holds.letters(claimed.lease, 10).map { it.letter.event.position })
            .containsExactly(1L, 3L)
    }

    @Test
    fun `generation cutover requires every checked-cover checkpoint to reach its immutable barrier`() {
        val database = RainPostgres.freshDatabase("projection_generation")
        val dataSource = database.dataSource()
        val transactions = DataSourceTransactionManager(dataSource)
        val dsl = DSL.using(TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES)
        RainSchemaMigrationStrategy(SchemaDescriptor.load().descriptors)
            .migrate(
                Flyway
                    .configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/none")
                    .failOnMissingLocations(false)
                    .load(),
            )
        val events = PostgresEventStore(dsl, transactions)
        val stream = StreamRef(EventNamespace.of(ByteArray(EventNamespace.SIZE_BYTES) { 7 }), "counter", "one")
        TransactionTemplate(transactions).execute {
            events.inCallerTransaction { transaction ->
                events.append(transaction, 0, encoded(stream, "1"), EventMetadata(OperationKey.of("generation-source")))
            }
        }
        val source = events.initialCursor()
        val barrier = events.readCommitted(source, 10).next
        val spec = sameUnitSpec()
        val plan =
            ProjectionGenerationPlan.create(
                spec.name,
                ProjectionGeneration(1),
                ProjectionCheckpointContract(events.origin, spec.revision, spec.cover.fingerprint),
                source,
                barrier,
                ProjectionEffectPolicy.STAGED_DURABLE,
            )
        val generations = PostgresProjectionGenerationStore(dsl)

        assertThat(generations.register(plan)).isInstanceOf(ProjectionGenerationRegistration.Created::class.java)
        assertThat(generations.markReady(plan, spec.cover)).isEqualTo(ProjectionGenerationReadiness.Behind(1, 0))

        val checkpoints = PostgresProjectionCheckpointStore(dsl, events.origin)
        val lane = ProjectionLane(spec.name, ProjectionGeneration(1), ProjectionPartition.WHOLE)
        val claim =
            checkpoints.claim(
                lane,
                plan.contract,
                source,
                Instant.now(),
                java.time.Duration.ofMinutes(1),
            ) as ProjectionClaim.Acquired
        assertThat(
            checkpoints.advance(claim.lease, barrier, Instant.now()),
        ).isInstanceOf(com.gd.rain.event.projection.ProjectionAdvance.Advanced::class.java)
        assertThat(generations.markReady(plan, spec.cover)).isInstanceOf(ProjectionGenerationReadiness.Ready::class.java)
        assertThat(
            generations.cutover(spec.name, null, ProjectionGeneration(1)),
        ).isInstanceOf(com.gd.rain.event.projection.ProjectionCutover.Activated::class.java)
        val gate = PostgresProjectionGenerationStore(dsl, transactions)
        TransactionTemplate(transactions).execute {
            assertThat(gate.admitEffect(spec.name, ProjectionGeneration(1), 1))
                .isEqualTo(ProjectionEffectAdmission.SUPPRESSED_BY_BARRIER)
            assertThat(gate.admitEffect(spec.name, ProjectionGeneration(1), 2))
                .isEqualTo(ProjectionEffectAdmission.ALLOWED)
        }
    }

    private fun sameUnitSpec(): ProjectionSpec =
        ProjectionSpec(
            name = ProjectionName.of("counter-view"),
            revision = ProjectionContractRevision(1),
            ownedFamilies = setOf("counter"),
            routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
            cover = ProjectionCover.whole(ByStream),
            advancement = ProjectionAdvancement.SAME_UNIT,
            destination = ProjectionDestinationId.of("counter-view-db"),
            permanentFailurePolicy = ProjectionPermanentFailurePolicy.HALT,
            effectPolicy = ProjectionEffectPolicy.DISABLED,
            budget = ProjectionRunBudget(10),
        )

    private fun parkSpec(): ProjectionSpec =
        ProjectionSpec(
            name = ProjectionName.of("counter-park"),
            revision = ProjectionContractRevision(1),
            ownedFamilies = setOf("counter"),
            routes = mapOf(ProjectionFact("counter", "counter.incremented") to ProjectionRoute.Handle),
            cover = ProjectionCover.whole(ByStream),
            advancement = ProjectionAdvancement.SAME_UNIT,
            destination = ProjectionDestinationId.of("counter-view-db"),
            permanentFailurePolicy = ProjectionPermanentFailurePolicy.PARK_SEQUENCE,
            effectPolicy = ProjectionEffectPolicy.DISABLED,
            budget = ProjectionRunBudget(10),
        )

    private object ByStream : SequenceKeyHasher {
        override val id: SequenceKeyHasherId = SequenceKeyHasherId.of("stream.v1")

        override fun hash(event: StoredEvent): Long =
            event.stream.key
                .hashCode()
                .toLong()

        override fun sequence(event: StoredEvent): com.gd.rain.event.projection.ProjectionSequenceId =
            com.gd.rain.event.projection.ProjectionSequenceId.forStream(id, event.stream)
    }

    private fun encoded(
        stream: StreamRef,
        value: String,
    ): EncodedChanges = EncodedChanges(stream, listOf(EncodedFact(FactType("counter.incremented", 1), EventBytes.utf8(value))))
}
