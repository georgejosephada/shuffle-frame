package com.gjnet.shuffleframe

import java.security.SecureRandom
import java.util.Random

/**
 * Endless shuffled sequence with history for going back. Items are drawn from shuffle bags:
 * everything is shown once (in a fresh random order every run) before anything repeats.
 * With [balanced] each album gets its own bag and bags are picked with equal probability,
 * so a small album gets as much screen time as a big one.
 */
class Playlist(private val rng: Random = SecureRandom()) {
    private class Bag(val pool: List<PlayItem>) {
        val queue = ArrayDeque<PlayItem>()
    }

    private var bags: List<Bag> = emptyList()
    private var valid: Map<String, PlayItem> = emptyMap()
    private val history = ArrayList<PlayItem>()
    private var pos = -1
    /** Furthest history position actually shown (beyond it, only a look-ahead). */
    private var maxShown = -1

    val isEmpty get() = valid.isEmpty()

    /** Whether [previous] has something to go back to. */
    val hasPrevious get() = (0 until pos.coerceAtMost(history.size)).any { valid.containsKey(history[it].key) }

    /**
     * Starts a new random order over everything: what was shown stays available for going back,
     * but anything queued (or looked ahead at) is dropped and every item is pending again.
     */
    fun reshuffle() {
        if (maxShown + 1 < history.size) history.subList(maxShown + 1, history.size).clear()
        pos = maxShown
        val last = history.lastOrNull()?.key
        bags = bags.map { old ->
            Bag(old.pool).also { b ->
                b.queue.addAll(old.pool.filter { valid.containsKey(it.key) }.shuffled(rng))
                if (b.queue.size > 1 && b.queue.first().key == last) b.queue.addLast(b.queue.removeFirst())
            }
        }
    }

    fun setItems(items: List<PlayItem>, balanced: Boolean) {
        val queued = bags.flatMapTo(HashSet()) { b -> b.queue.map { it.key } }
        val known = bags.flatMapTo(HashSet()) { b -> b.pool.map { it.key } }
        valid = items.associateBy { it.key }
        val groups = if (balanced) items.groupBy { it.albumKey }.values.toList()
        else listOf(items.distinctBy { it.key })
        bags = groups.map { group ->
            Bag(group).also { bag ->
                // Keep this round going: unshown items stay pending and new arrivals join them.
                bag.queue.addAll(group.filter { it.key in queued || it.key !in known }.shuffled(rng))
            }
        }
    }

    /** Stops showing [key] right away, before the next [setItems] catches up. */
    fun remove(key: String) {
        if (!valid.containsKey(key)) return
        valid = valid - key
        bags = bags.map { old ->
            Bag(old.pool.filter { it.key != key }).also { b -> b.queue.addAll(old.queue.filter { it.key != key }) }
        }
    }

    fun next(): PlayItem? {
        while (pos + 1 < history.size) {
            pos++
            maxShown = maxOf(maxShown, pos)
            valid[history[pos].key]?.let { return it }
        }
        val item = draw() ?: return null
        history.add(item)
        pos = history.size - 1
        maxShown = pos
        trim()
        return item
    }

    /** The item [next] will return, without advancing. */
    fun peekNext(): PlayItem? {
        for (i in pos + 1 until history.size) valid[history[i].key]?.let { return it }
        val item = draw() ?: return null
        history.add(item)
        return item
    }

    fun previous(): PlayItem? {
        var p = pos
        while (p > 0) {
            p--
            valid[history[p].key]?.let { pos = p; return it }
        }
        return null
    }

    private fun draw(): PlayItem? {
        val live = bags.filter { it.pool.isNotEmpty() }
        if (live.isEmpty()) return null
        val last = history.lastOrNull()?.key
        var item: PlayItem? = null
        repeat(3) {
            val bag = live[rng.nextInt(live.size)]
            if (bag.queue.isEmpty()) {
                bag.queue.addAll(bag.pool.shuffled(rng))
                if (bag.queue.size > 1 && bag.queue.first().key == last) bag.queue.addLast(bag.queue.removeFirst())
            }
            item = bag.queue.removeFirst()
            if (item!!.key != last || valid.size == 1) return item
        }
        return item
    }

    private fun trim() {
        if (history.size > 600) {
            history.subList(0, 100).clear()
            pos -= 100
            maxShown -= 100
        }
    }
}
