package vn.edu.mobileoptimization.debounce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConnectionKeeperTest {
    @Test fun startsOnceStopsAndRestarts() {
        val events = LinkedBlockingQueue<Long>()
        val keeper = ConnectionKeeper(intervalMs = 200, reportState = { _, _ -> }) { events.put(System.nanoTime()) }
        try {
            keeper.start()
            keeper.start()
            val first = events.poll(2, TimeUnit.SECONDS)
            val second = events.poll(2, TimeUnit.SECONDS)
            assertNotNull(first)
            assertNotNull(second)
            assertTrue("start() hai lần không được tạo hai lịch riêng", TimeUnit.NANOSECONDS.toMillis(second!! - first!!) >= 100)
            keeper.stop()
            // A request already executing at stop is permitted to complete.
            Thread.sleep(250)
            events.clear()
            assertNull(events.poll(500, TimeUnit.MILLISECONDS))
            keeper.start()
            assertNotNull(events.poll(2, TimeUnit.SECONDS))
            keeper.close()
            Thread.sleep(250)
            events.clear()
            keeper.start()
            assertNull(events.poll(500, TimeUnit.MILLISECONDS))
        } finally {
            keeper.close()
        }
    }

    @Test fun aFailureDoesNotStopLaterHeartbeats() {
        val attempts = AtomicInteger()
        val events = LinkedBlockingQueue<Int>()
        val states = LinkedBlockingQueue<Boolean>()
        val keeper = ConnectionKeeper(intervalMs = 50, reportState = { connected, _ -> states.put(connected) }) {
            val n = attempts.incrementAndGet()
            if (n == 1) throw java.io.IOException("test")
            events.put(n)
        }
        try {
            keeper.start()
            val success = events.poll(2, TimeUnit.SECONDS)
            assertNotNull(success)
            assertTrue(success!! >= 2)
            assertEquals(false, states.poll(2, TimeUnit.SECONDS))
            assertEquals(true, states.poll(2, TimeUnit.SECONDS))
            keeper.stop()
        } finally {
            keeper.close()
        }
    }
}
