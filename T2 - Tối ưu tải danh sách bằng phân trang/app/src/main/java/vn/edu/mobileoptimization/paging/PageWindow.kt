package vn.edu.mobileoptimization.paging

internal fun pageCount(total: Int, pageSize: Int): Int {
    require(total >= 0 && pageSize > 0)
    return ((total.toLong() + pageSize - 1) / pageSize).toInt()
}

/** Keeps the visible range stable until the selected page leaves it. A null entry is an ellipsis. */
internal class PageWindow {
    private var first = 1

    fun reset() {
        first = 1
    }

    fun pages(current: Int, totalPages: Int): List<Int?> {
        require(totalPages >= 0)
        if (totalPages == 0) {
            first = 1
            return emptyList()
        }
        require(current in 1..totalPages)
        if (totalPages <= 5) {
            first = 1
            return (1..totalPages).map { it }
        }

        val lastStart = totalPages - 4
        first = first.coerceIn(1, lastStart)
        if (current < first) first = current
        val visibleEnd = if (first == lastStart) totalPages else first + 2
        if (current > visibleEnd) first = (current - 2).coerceAtMost(lastStart)

        if (first == lastStart) return (first..totalPages).map { it }
        return (first..first + 2).map<Int, Int?> { it } + listOf(null, totalPages)
    }
}
