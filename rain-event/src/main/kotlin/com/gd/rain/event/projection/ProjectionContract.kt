package com.gd.rain.event.projection

import com.gd.rain.event.EventCatalogue
import com.gd.rain.event.EventLimits
import com.gd.rain.event.EventLogMark
import com.gd.rain.event.EventLogOrigin
import com.gd.rain.event.StoredEvent
import com.gd.rain.event.StreamRef
import java.security.MessageDigest
import java.util.TreeMap

/** Globally stable declared projection identity, independent of a Kotlin class or bean name. */
public class ProjectionName private constructor(
    private val value: String,
) {
    public fun text(): String = value

    override fun equals(other: Any?): Boolean = other is ProjectionName && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "projection:$value"

    public companion object {
        public fun of(value: String): ProjectionName {
            require(StreamRef.NAME.matches(value)) { "projection name is not a stable identifier" }
            return ProjectionName(value)
        }
    }
}

/** Application-owned semantic contract revision; ordinary source refactors do not change it. */
public data class ProjectionContractRevision(
    public val value: Int,
) {
    init {
        require(value > 0) { "projection contract revision is positive" }
    }
}

/** Stable declared identifier for the function that maps an envelope to its sequence key. */
public class SequenceKeyHasherId private constructor(
    private val value: String,
) {
    public fun text(): String = value

    override fun equals(other: Any?): Boolean = other is SequenceKeyHasherId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "projection-sequence-hasher:$value"

    public companion object {
        public fun of(value: String): SequenceKeyHasherId {
            require(StreamRef.NAME.matches(value)) { "projection sequence hasher id is not stable" }
            return SequenceKeyHasherId(value)
        }
    }
}

/** Maps one immutable envelope to a deterministic sequence-key hash; it must not consult mutable state. */
public interface SequenceKeyHasher {
    public val id: SequenceKeyHasherId

    public fun hash(event: StoredEvent): Long

    /**
     * Returns the opaque causal sequence identity used by parking/redrive.
     *
     * It is intentionally distinct from [hash]: a 64-bit partition hash may collide and therefore cannot safely
     * identify a queue. Implementations that use one source stream per sequence may call
     * [ProjectionSequenceId.forStream].
     */
    public fun sequence(event: StoredEvent): ProjectionSequenceId
}

/**
 * One immutable binary-prefix partition over the low-order bits of a sequence-key hash.
 *
 * The representation admits mixed-depth covers, so scale-out is a checked split tree rather than
 * a `% N` convention that would remap live sequences when the worker count changes.
 */
public data class ProjectionPartition(
    public val depth: Int,
    public val prefix: Long,
) {
    init {
        require(depth in 0..MAX_DEPTH) { "projection partition depth is outside 0..$MAX_DEPTH" }
        require(prefix >= 0 && prefix <= mask(depth)) { "projection partition prefix does not fit its depth" }
    }

    /** Stable durable representation used in topology rows and commands. */
    public val canonical: String = "p$depth:$prefix"

    public fun contains(hash: Long): Boolean = (hash and mask(depth)) == prefix

    /** The only permitted topology expansion: one parent becomes exactly two children. */
    public fun split(): Pair<ProjectionPartition, ProjectionPartition> {
        require(depth < MAX_DEPTH) { "projection partition has reached the maximum split depth" }
        val childDepth = depth + 1
        return ProjectionPartition(childDepth, prefix) to ProjectionPartition(childDepth, prefix or (1L shl depth))
    }

    public companion object {
        public const val MAX_DEPTH: Int = 62

        public val WHOLE: ProjectionPartition = ProjectionPartition(0, 0)

        internal fun mask(depth: Int): Long = if (depth == 0) 0 else (1L shl depth) - 1
    }
}

/** 32-byte durable fingerprint of a checked sequence-key hasher and exact partition cover. */
public class ProjectionTopologyFingerprint private constructor(
    private val value: ByteArray,
) {
    public fun copy(): ByteArray = value.copyOf()

    override fun equals(other: Any?): Boolean = other is ProjectionTopologyFingerprint && MessageDigest.isEqual(value, other.value)

    override fun hashCode(): Int = value.contentHashCode()

    override fun toString(): String = "projection-topology-fingerprint[redacted]"

    public companion object {
        public const val BYTES: Int = 32

        public fun of(value: ByteArray): ProjectionTopologyFingerprint {
            require(value.size == BYTES) { "projection topology fingerprint has ${value.size} bytes, not $BYTES" }
            return ProjectionTopologyFingerprint(value.copyOf())
        }
    }
}

/**
 * A non-empty immutable exact cover of the full sequence-key hash space.
 *
 * Construction rejects overlaps and holes arithmetically. The resulting set is safe to persist as
 * one generation's topology and can only be changed by replacing a member with [split]'s children.
 */
public class ProjectionCover private constructor(
    public val hasher: SequenceKeyHasher,
    members: Collection<ProjectionPartition>,
) {
    public val members: List<ProjectionPartition> = members.sortedWith(compareBy(ProjectionPartition::depth, ProjectionPartition::prefix))
    public val fingerprint: ProjectionTopologyFingerprint = fingerprint(hasher.id, this.members)

    init {
        require(this.members.isNotEmpty()) { "projection cover has no partitions" }
        require(this.members.size <= MAX_MEMBERS) { "projection cover exceeds $MAX_MEMBERS partitions" }
        require(this.members.distinct().size == this.members.size) { "projection cover has duplicate partitions" }
        require(isExact(this.members)) { "projection cover has a hole or overlap" }
    }

    /** The unique partition which owns [event], or a refusal if a corrupted cover violates its invariant. */
    public fun partitionFor(event: StoredEvent): ProjectionPartition {
        val matches = members.filter { it.contains(hasher.hash(event)) }
        return checkNotNull(matches.singleOrNull()) { "projection cover does not own exactly one sequence key" }
    }

    /** Replaces exactly one live member with its two children; merge and implicit reshards are absent by design. */
    public fun split(parent: ProjectionPartition): ProjectionCover {
        require(parent in members) { "projection split parent is not in this cover" }
        val children = parent.split()
        return ProjectionCover(hasher, members.filterNot { it == parent } + children.toList())
    }

    public companion object {
        public const val MAX_MEMBERS: Int = 1_024

        public fun whole(hasher: SequenceKeyHasher): ProjectionCover = ProjectionCover(hasher, listOf(ProjectionPartition.WHOLE))

        public fun of(
            hasher: SequenceKeyHasher,
            members: Collection<ProjectionPartition>,
        ): ProjectionCover = ProjectionCover(hasher, members)

        private fun isExact(members: List<ProjectionPartition>): Boolean {
            val root = CoverNode()
            members.forEach { partition -> root.insert(partition) }
            return root.isComplete()
        }

        private fun fingerprint(
            hasher: SequenceKeyHasherId,
            members: List<ProjectionPartition>,
        ): ProjectionTopologyFingerprint =
            ProjectionTopologyFingerprint.of(
                MessageDigest.getInstance("SHA-256").run {
                    update("rain.event.projection.topology.v1".toByteArray(Charsets.UTF_8))
                    update(0)
                    update(hasher.text().toByteArray(Charsets.UTF_8))
                    members.forEach { partition ->
                        update(0)
                        update(partition.canonical.toByteArray(Charsets.UTF_8))
                    }
                    digest()
                },
            )
    }

    private class CoverNode {
        private var member: ProjectionPartition? = null
        private val children: MutableMap<Int, CoverNode> = TreeMap()

        fun insert(partition: ProjectionPartition) {
            var node = this
            repeat(partition.depth) { bit ->
                require(node.member == null) { "projection cover partitions overlap" }
                val value = ((partition.prefix ushr bit) and 1L).toInt()
                node = node.children.getOrPut(value, ::CoverNode)
            }
            require(node.member == null && node.children.isEmpty()) { "projection cover partitions overlap" }
            node.member = partition
        }

        fun isComplete(): Boolean = member != null || (children[0]?.isComplete() == true && children[1]?.isComplete() == true)
    }
}

/** A stable fact wire address used to require an explicit projection decision. */
public data class ProjectionFact(
    public val family: String,
    public val type: String,
) {
    init {
        require(StreamRef.NAME.matches(family)) { "projection fact family is not stable" }
        require(StreamRef.NAME.matches(type)) { "projection fact type is not stable" }
    }
}

/** Each fact of an owned family is handled or deliberately ignored with a bounded reason. */
public sealed interface ProjectionRoute {
    public data object Handle : ProjectionRoute

    public data class Ignore(
        public val why: String,
    ) : ProjectionRoute {
        init {
            require(why.isNotBlank() && why.toByteArray(Charsets.UTF_8).size <= MAX_REASON_BYTES) {
                "projection ignore reason is blank or too large"
            }
        }
    }

    public companion object {
        public const val MAX_REASON_BYTES: Int = 512
    }
}

/** Delivery semantics are explicit: only a separately proven same transaction authority permits SAME_UNIT. */
public enum class ProjectionAdvancement {
    SAME_UNIT,
    AFTER_APPLY,
}

/** Failure policy for a classified permanent error. PARK_SEQUENCE is valid only for SAME_UNIT delivery. */
public enum class ProjectionPermanentFailurePolicy {
    HALT,
    PARK_SEQUENCE,
}

/** External effects are disabled unless the same-unit projection protocol stages a durable effect record. */
public enum class ProjectionEffectPolicy {
    DISABLED,
    STAGED_DURABLE,
}

/** Stable declared destination identity; it is never a JDBC URL or a connection credential. */
public class ProjectionDestinationId private constructor(
    private val value: String,
) {
    public fun text(): String = value

    override fun equals(other: Any?): Boolean = other is ProjectionDestinationId && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "projection-destination:$value"

    public companion object {
        public fun of(value: String): ProjectionDestinationId {
            require(StreamRef.NAME.matches(value)) { "projection destination id is not stable" }
            return ProjectionDestinationId(value)
        }
    }
}

/** Bounded worker work declaration; concurrency is scheduled outside this pure contract. */
public data class ProjectionRunBudget(
    public val pageSize: Int,
    public val maxPages: Int = 1,
) {
    init {
        require(pageSize in 1..EventLimits.HARD_PAGE_SIZE) { "projection page size is outside the hard event limit" }
        require(maxPages in 1..MAX_PAGES) { "projection max pages is outside 1..$MAX_PAGES" }
    }

    public companion object {
        public const val MAX_PAGES: Int = 128
    }
}

/** Explicit projection declaration. It deliberately contains no code hash, bean lookup, or command capability. */
public class ProjectionSpec(
    public val name: ProjectionName,
    public val revision: ProjectionContractRevision,
    ownedFamilies: Set<String>,
    routes: Map<ProjectionFact, ProjectionRoute>,
    public val cover: ProjectionCover,
    public val advancement: ProjectionAdvancement,
    public val destination: ProjectionDestinationId,
    public val permanentFailurePolicy: ProjectionPermanentFailurePolicy,
    public val effectPolicy: ProjectionEffectPolicy,
    public val budget: ProjectionRunBudget,
) {
    public val ownedFamilies: Set<String> = ownedFamilies.toSortedSet()
    public val routes: Map<ProjectionFact, ProjectionRoute> = routes.toSortedMap(compareBy(ProjectionFact::family, ProjectionFact::type))

    init {
        require(this.ownedFamilies.isNotEmpty()) { "projection owns no event families" }
        require(this.ownedFamilies.all(StreamRef.NAME::matches)) { "projection owns an invalid event family" }
        require(this.routes.isNotEmpty()) { "projection has no explicit fact routes" }
        require(this.routes.keys.all { it.family in this.ownedFamilies }) { "projection routes a fact outside its owned families" }
        require(
            !(
                advancement == ProjectionAdvancement.AFTER_APPLY &&
                    permanentFailurePolicy == ProjectionPermanentFailurePolicy.PARK_SEQUENCE
            ),
        ) {
            "park sequence requires same-unit projection delivery"
        }
        require(!(advancement == ProjectionAdvancement.AFTER_APPLY && effectPolicy == ProjectionEffectPolicy.STAGED_DURABLE)) {
            "durable effects require same-unit projection delivery"
        }
    }

    /** Refuses unknown facts in an owned family; facts outside that ownership are not this projection's input. */
    public fun route(event: StoredEvent): ProjectionRoute? =
        if (event.stream.family !in ownedFamilies) {
            null
        } else {
            checkNotNull(routes[ProjectionFact(event.stream.family, event.fact.name)]) {
                "projection has no route for an owned fact"
            }
        }

    internal fun validateRoutes(catalogue: EventCatalogue) {
        val declared =
            ownedFamilies
                .flatMap { family ->
                    catalogue.aggregate(family).facts.map { fact -> ProjectionFact(family, fact.type) }
                }.toSet()
        require(routes.keys == declared) { "projection does not route every declared fact of its owned families" }
    }
}

/** Read-only delivery object. Projectors receive envelopes only, never EventStore/EventRepository append capability. */
public class ProjectionBatch(
    public val projection: ProjectionName,
    events: List<StoredEvent>,
    public val next: com.gd.rain.event.EventLogCursor,
) {
    public val events: List<StoredEvent> = events.toList()

    init {
        require(events.zipWithNext().all { (left, right) -> left.position < right.position }) {
            "projection batch events are not strictly ordered"
        }
        require(events.all { it.position <= next.deliveredPosition }) { "projection batch exceeds its cursor" }
    }
}

/** Application handler port. A runtime decides transaction placement and invokes it once per claimed batch. */
public fun interface ProjectionHandler {
    public fun apply(batch: ProjectionBatch): Unit
}

/** A named handler paired with its immutable declaration; its callback is never discovered by reflection. */
public data class ProjectionDefinition(
    public val spec: ProjectionSpec,
    public val handler: ProjectionHandler,
)

/**
 * Startup-validated projection registry.
 *
 * It ties each projection's owned families to the explicit event catalogue so a newly declared
 * fact cannot silently fall through an existing projection during a rolling deployment.
 */
public class ProjectionCatalogue(
    eventCatalogue: EventCatalogue,
    definitions: Set<ProjectionDefinition>,
) {
    public val definitions: Set<ProjectionDefinition> = definitions.toSet()
    private val byName: Map<ProjectionName, ProjectionDefinition>

    init {
        require(this.definitions.isNotEmpty()) { "projection catalogue declares no projections" }
        byName = this.definitions.associateBy { it.spec.name }
        require(byName.size == this.definitions.size) { "projection catalogue declares a projection name more than once" }
        this.definitions.forEach { definition -> definition.spec.validateRoutes(eventCatalogue) }
    }

    public fun projection(name: ProjectionName): ProjectionDefinition = checkNotNull(byName[name]) { "projection is not declared" }
}

/** Typed projection-facing name for a durable event-log mark. */
public data class ProjectionMark(
    public val origin: EventLogOrigin,
    public val position: Long,
) {
    init {
        require(position > 0) { "projection mark position is positive" }
    }

    public companion object {
        public fun from(mark: EventLogMark): ProjectionMark = ProjectionMark(mark.origin, mark.position)
    }
}
