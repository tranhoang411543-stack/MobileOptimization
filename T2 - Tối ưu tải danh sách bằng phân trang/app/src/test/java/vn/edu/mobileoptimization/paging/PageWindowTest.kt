package vn.edu.mobileoptimization.paging

import org.junit.Assert.assertEquals
import org.junit.Test

class PageWindowTest {
    @Test fun pageCountIncludesTheLastPartialPage() {
        assertEquals(0, pageCount(0, 20))
        assertEquals(1, pageCount(1, 20))
        assertEquals(50, pageCount(1000, 20))
        assertEquals(51, pageCount(1003, 20))
        assertEquals(107374183, pageCount(Int.MAX_VALUE, 20))
    }

    @Test fun windowStaysStableWhenMovingBackWithinVisiblePages() {
        val window = PageWindow()
        assertEquals(listOf(1, 2, 3, null, 10), window.pages(1, 10))
        assertEquals(listOf(1, 2, 3, null, 10), window.pages(2, 10))
        assertEquals(listOf(1, 2, 3, null, 10), window.pages(3, 10))
        assertEquals(listOf(2, 3, 4, null, 10), window.pages(4, 10))
        assertEquals(listOf(2, 3, 4, null, 10), window.pages(3, 10))
        assertEquals(listOf(2, 3, 4, null, 10), window.pages(2, 10))
        assertEquals(listOf(1, 2, 3, null, 10), window.pages(1, 10))
    }

    @Test fun lastFivePagesRemainVisibleUntilMovingBeforeThem() {
        val window = PageWindow()
        for (page in 1..7) window.pages(page, 10)
        assertEquals(listOf(5, 6, 7, null, 10), window.pages(7, 10))
        assertEquals(listOf(6, 7, 8, 9, 10), window.pages(8, 10))
        assertEquals(listOf(6, 7, 8, 9, 10), window.pages(9, 10))
        assertEquals(listOf(6, 7, 8, 9, 10), window.pages(10, 10))
        assertEquals(listOf(6, 7, 8, 9, 10), window.pages(6, 10))
        assertEquals(listOf(5, 6, 7, null, 10), window.pages(5, 10))
    }

    @Test fun shortAndEmptyListsDoNotShowAnEllipsis() {
        val window = PageWindow()
        assertEquals(emptyList<Int?>(), window.pages(1, 0))
        assertEquals(listOf(1), window.pages(1, 1))
        assertEquals(listOf(1, 2, 3, 4, 5), window.pages(3, 5))
        assertEquals(listOf(497, 498, 499, 500, 501), window.pages(501, 501))
        window.reset()
        assertEquals(listOf(1, 2, 3, null, 501), window.pages(1, 501))
    }
}
