package com.gd.rain.web.limit

/**
 * The bounded set of token buckets a throttle remembers, ordered by when each is full again.
 *
 * The bound is the security property: without it a fresh caller key per request grows the table until
 * the process dies. The eviction rule is the other half — only a bucket that has refilled to full may
 * be forgotten, because a caller the table has never seen starts full, so forgetting a full bucket
 * changes nothing, while forgetting a drained one would hand its caller a free reset.
 *
 * A full table refuses only callers it does not already hold; callers it holds keep being admitted by
 * their own buckets. Every refusal of a new caller because the table is full is counted in
 * [refusedBecauseFull].
 *
 * Finding the bucket to forget costs one look at the root of a heap keyed by refill instant, whatever
 * the table's size. The heap is indexed (each entry knows its position) so a bucket whose refill
 * instant moves — on every admitted request — is re-sifted in place in O(log n).
 *
 * Not thread-safe on its own: [TokenBucketThrottle] holds the lock.
 */
public class CallerTable(
    public val capacity: Int,
) {
    init {
        require(capacity > 0) { "a caller table holds at least one caller, got $capacity" }
    }

    /** A caller's bucket; the heap holds the same instance the map does. */
    public class Allowance internal constructor(
        public val caller: String,
        tokens: Double,
        at: Long,
    ) {
        public var tokens: Double = tokens
            internal set
        public var at: Long = at
            internal set
        public var fullAt: Long = at
            internal set
        internal var position: Int = -1
    }

    private val allowances = HashMap<String, Allowance>()
    private val heap = ArrayList<Allowance>()
    private var refusedFull: Long = 0

    /** Entries examined to make room for new callers; one per attempt, whatever the table's size. */
    internal var examinedForRoom: Long = 0
        private set

    public val size: Int get() = allowances.size

    /** How many new callers were refused because every seat was held by a bucket that was not yet full. */
    public val refusedBecauseFull: Long get() = refusedFull

    public operator fun get(caller: String): Allowance? = allowances[caller]

    /** Seats a new caller with [tokens], or answers null when the table is full of buckets that are not yet full. */
    public fun admit(
        caller: String,
        tokens: Double,
        now: Long,
    ): Allowance? {
        require(caller !in allowances) { "caller \"$caller\" already has a seat" }
        if (allowances.size >= capacity && !forgetRefilled(now)) {
            refusedFull++
            return null
        }
        val admitted = Allowance(caller, tokens, now)
        allowances[caller] = admitted
        push(admitted)
        return admitted
    }

    /** The bucket's refill instant moved; it sinks or rises from where it is. */
    public fun reschedule(
        allowance: Allowance,
        fullAt: Long,
    ) {
        allowance.fullAt = fullAt
        val position = allowance.position
        check(position >= 0 && heap[position] === allowance) { "caller \"${allowance.caller}\" has no seat in this table" }
        if (!siftUp(position)) siftDown(position)
    }

    private fun forgetRefilled(now: Long): Boolean {
        examinedForRoom++
        val root = heap.firstOrNull() ?: return false
        if (root.fullAt > now) return false
        pop()
        allowances.remove(root.caller)
        return true
    }

    private fun push(entry: Allowance) {
        heap += entry
        entry.position = heap.size - 1
        siftUp(heap.size - 1)
    }

    private fun pop() {
        val last = heap.size - 1
        swap(0, last)
        heap.removeAt(last).position = -1
        if (heap.isNotEmpty()) siftDown(0)
    }

    private fun siftUp(from: Int): Boolean {
        var index = from
        while (index > 0) {
            val parent = (index - 1) / 2
            if (heap[parent].fullAt <= heap[index].fullAt) break
            swap(parent, index)
            index = parent
        }
        return index != from
    }

    private fun siftDown(from: Int) {
        var index = from
        while (true) {
            val left = index * 2 + 1
            if (left >= heap.size) return
            val right = left + 1
            val smaller = if (right < heap.size && heap[right].fullAt < heap[left].fullAt) right else left
            if (heap[index].fullAt <= heap[smaller].fullAt) return
            swap(index, smaller)
            index = smaller
        }
    }

    private fun swap(
        first: Int,
        second: Int,
    ) {
        val held = heap[first]
        heap[first] = heap[second]
        heap[second] = held
        heap[first].position = first
        heap[second].position = second
    }
}
