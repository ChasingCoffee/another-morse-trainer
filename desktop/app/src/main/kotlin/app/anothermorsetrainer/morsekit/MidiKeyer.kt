package app.anothermorsetrainer.morsekit

import kotlin.math.abs

/**
 * A MIDI key's contacts → the one logical key. The straight-key note keys as
 * it comes (a straight key, or a Vail Adapter's own keyer). Dit and dah come
 * only from a paddle with no keyer of its own (the N6ARA TinyMIDI, a Vail
 * Adapter in Passthrough), so [PaddleKeyer] times them; they used to key as
 * straight-key contacts, so holding dit sounded one long tone.
 *
 * Pure and clock-free: the caller supplies every timestamp. Pinned by
 * `fixtures/midi-keyer.json`, which the iOS harness reads too. Mirrors iOS
 * `MIDIKeyer`.
 */
class MidiKeyer(mode: PaddleKeyer.Mode, wpm: Double) {

    /**
     * One change of the logical key. [timedByApp] is true when the app's
     * keyer timed it (from a dit or dah contact), false when the straight-key
     * contact keyed it as it came.
     */
    data class Edge(val isDown: Boolean, val atMs: Double, val timedByApp: Boolean)

    private enum class Half { STRAIGHT, KEYER }

    private var keyer = PaddleKeyer(mode, wpm)
    private val merge = KeyMerge<Half>()
    private var straightHeld = false
    /** A mode and speed [configure] asked for while the keyer was busy. */
    private var pending: Pair<PaddleKeyer.Mode, Double>? = null

    val mode: PaddleKeyer.Mode get() = keyer.mode
    val unitMs: Double get() = keyer.unitMs

    /** True while an element is in progress or the straight contact is held. */
    val isBusy: Boolean get() = keyer.isBusy || straightHeld

    /** When [advance] is next needed; null when the keyer is idle. */
    val nextDeadlineMs: Double? get() = keyer.nextDeadlineMs

    /**
     * Time the paddles at [mode] and [wpm] from [atMs]. A change made while
     * the keyer is busy waits until it is idle, so nothing in flight is
     * re-timed; of several waiting changes the last wins. Returns the edges
     * the clock owed by [atMs].
     */
    fun configure(mode: PaddleKeyer.Mode, wpm: Double, atMs: Double): List<Edge> {
        val edges = route(keyer.advance(atMs))
        pending = mode to wpm
        takePendingIfIdle()
        return edges
    }

    /**
     * One contact of the key went down or up at [atMs]. Returns every edge due
     * by then: those the keyer's clock owed first, then this one's.
     */
    fun contact(paddle: MidiKeyPaddle, isDown: Boolean, atMs: Double): List<Edge> {
        // Run the clock on first, so a change that was waiting for the
        // keyer to go idle is in place before this contact starts anything.
        val edges = route(keyer.advance(atMs)).toMutableList()
        takePendingIfIdle()
        when (paddle) {
            MidiKeyPaddle.STRAIGHT -> if (straightHeld != isDown) {
                straightHeld = isDown
                merge.update(Half.STRAIGHT, isDown)?.let { edges += Edge(it, atMs, timedByApp = false) }
            }
            MidiKeyPaddle.DIT -> edges += route(keyer.paddle(PaddleKeyer.Element.DIT, isDown, atMs))
            MidiKeyPaddle.DAH -> edges += route(keyer.paddle(PaddleKeyer.Element.DAH, isDown, atMs))
        }
        return edges
    }

    /** Run the keyer's clock on to [toMs]. */
    fun advance(toMs: Double): List<Edge> {
        val edges = route(keyer.advance(toMs))
        takePendingIfIdle()
        return edges
    }

    /**
     * The key went away (unplugged, or the screen is closing): let go of every
     * contact and cut the keyer's tone at [atMs].
     */
    fun releaseAll(atMs: Double): List<Edge> {
        val edges = route(keyer.advance(atMs)).toMutableList()
        edges += route(keyer.releaseAll(atMs))
        if (straightHeld) {
            straightHeld = false
            merge.update(Half.STRAIGHT, false)?.let { edges += Edge(it, atMs, timedByApp = false) }
        }
        takePendingIfIdle()
        return edges
    }

    /**
     * Swap in a keyer with the waiting mode and speed, if the keyer is idle.
     * Only the paddle keyer is replaced: the straight contact and the merge
     * carry on, and an idle keyer holds nothing a fresh one would lose.
     */
    private fun takePendingIfIdle() {
        val (mode, wpm) = pending ?: return
        if (keyer.isBusy) return
        pending = null
        if (mode != keyer.mode || abs(1200.0 / wpm.coerceAtLeast(1.0) - keyer.unitMs) > 1e-9) {
            keyer = PaddleKeyer(mode, wpm)
        }
    }

    /** The keyer's tone edges, as changes of the logical key. */
    private fun route(keyed: List<PaddleKeyer.Edge>): List<Edge> =
        keyed.mapNotNull { edge -> merge.update(Half.KEYER, edge.isDown)?.let { Edge(it, edge.atMs, timedByApp = true) } }
}
