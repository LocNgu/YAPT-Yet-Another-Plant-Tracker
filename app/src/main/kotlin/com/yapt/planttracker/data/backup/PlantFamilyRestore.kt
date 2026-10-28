package com.yapt.planttracker.data.backup

/**
 * The parent links a restore may apply (#960, product ADR-0072). A link is kept only when its parent is
 * one of [plantIds] and it isn't part of a cycle; self-links, dangling links and every link on a cycle
 * are dropped, so a hand-edited or corrupt backup can never plant a loop in the family tree. The links
 * are applied in a second pass after every plant row exists, which is also why none can dangle at insert.
 */
internal fun sanitizePropagatedLinks(plantIds: Set<Long>, rawLinks: Map<Long, Long?>): Map<Long, Long> {
    val candidates = mutableMapOf<Long, Long>()
    for ((child, parent) in rawLinks) {
        if (parent != null && child in plantIds && parent in plantIds && parent != child) candidates[child] = parent
    }
    return candidates.filter { (child, _) -> !isOnCycle(child, candidates) }
}

private fun isOnCycle(start: Long, parentOf: Map<Long, Long>): Boolean {
    val seen = mutableSetOf<Long>()
    var current = parentOf[start]
    while (current != null && seen.add(current)) {
        if (current == start) return true
        current = parentOf[current]
    }
    return false
}
