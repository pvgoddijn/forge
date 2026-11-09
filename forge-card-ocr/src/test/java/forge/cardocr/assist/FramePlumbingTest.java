package forge.cardocr.assist;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class FramePlumbingTest {

    private static ByteBuffer plane(int stride, int w, int h, boolean shortLastRow) {
        int size = shortLastRow ? stride * (h - 1) + w : stride * h;
        ByteBuffer buffer = ByteBuffer.allocateDirect(size);
        for (int i = 0; i < size; i++) {
            buffer.put(i, (byte) 0x7F);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buffer.put(y * stride + x, (byte) (y * 10 + x));
            }
        }
        return buffer;
    }

    private static void assertCompacted(byte[] out, int w, int h) {
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                assertEquals((byte) (y * 10 + x), out[y * w + x], x + "," + y);
            }
        }
    }

    @Test
    void removesRowPadding() {
        byte[] out = new byte[20];
        YPlane.compact(plane(8, 5, 4, false), 8, 1, 5, 4, out);
        assertCompacted(out, 5, 4);
    }

    @Test
    void handlesNoPadding() {
        byte[] out = new byte[20];
        YPlane.compact(plane(5, 5, 4, false), 5, 1, 5, 4, out);
        assertCompacted(out, 5, 4);
    }

    @Test
    void handlesABufferThatEndsAtTheLastPixel() {
        byte[] out = new byte[20];
        YPlane.compact(plane(8, 5, 4, true), 8, 1, 5, 4, out);
        assertCompacted(out, 5, 4);
    }

    @Test
    void handlesPixelStrideAboveOne() {
        ByteBuffer buffer = ByteBuffer.allocate(16);
        for (int i = 0; i < 16; i++) {
            buffer.put(i, (byte) i);
        }
        byte[] out = new byte[4];
        YPlane.compact(buffer, 8, 2, 2, 2, out);
        assertArrayEquals(new byte[] {0, 2, 8, 10}, out);
    }

    @Test
    void leavesTheBufferPositionAlone() {
        ByteBuffer buffer = plane(8, 5, 4, false);
        YPlane.compact(buffer, 8, 1, 5, 4, new byte[20]);
        assertEquals(0, buffer.position());
    }

    @Test
    void slotKeepsOnlyTheNewestAndCountsDrops() {
        LatestFrameSlot<String> slot = new LatestFrameSlot<>();
        slot.publish("a");
        slot.publish("b");
        slot.publish("c");
        assertEquals("c", slot.take());
        assertEquals(2, slot.dropped());
    }

    @Test
    void slotRecyclesDroppedAndReleasedFrames() {
        LatestFrameSlot<String> slot = new LatestFrameSlot<>();
        assertNull(slot.acquire());
        slot.publish("a");
        slot.publish("b");
        assertEquals("a", slot.acquire());
        assertEquals("b", slot.take());
        slot.release("b");
        assertEquals("b", slot.acquire());
    }

    @Test
    void slowConsumerOnARealThreadSeesOnlyIncreasingFrames() throws Exception {
        LatestFrameSlot<Integer> slot = new LatestFrameSlot<>();
        AtomicInteger seen = new AtomicInteger();
        AtomicInteger last = new AtomicInteger(-1);
        AtomicBoolean ordered = new AtomicBoolean(true);
        Thread consumer = new Thread(() -> {
            Integer frame;
            while ((frame = slot.take()) != null) {
                ordered.compareAndSet(true, frame > last.get());
                last.set(frame);
                seen.incrementAndGet();
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        consumer.start();
        for (int i = 0; i < 5000; i++) {
            slot.publish(i);
            if (i % 100 == 0) {
                Thread.sleep(1);
            }
        }
        Thread.sleep(20);
        slot.close();
        consumer.join(2000);
        assertFalse(consumer.isAlive());
        assertTrue(ordered.get());
        assertEquals(4999, last.get());
        assertTrue(seen.get() < 5000 && slot.dropped() > 0);
    }

    @Test
    void closeWakesAWaitingConsumerAndStopsDelivery() throws Exception {
        LatestFrameSlot<String> slot = new LatestFrameSlot<>();
        CompletableFuture<String> taken = CompletableFuture.supplyAsync(slot::take);
        Thread.sleep(20);
        slot.close();
        assertNull(taken.get(2, TimeUnit.SECONDS));
        slot.publish("late");
        assertNull(slot.take());
        slot.release("late");
        assertNull(slot.acquire());
    }

    @Test
    void closeDiscardsAnUntakenFrame() {
        LatestFrameSlot<String> slot = new LatestFrameSlot<>();
        slot.publish("a");
        slot.close();
        assertNull(slot.take());
    }

    @Test
    void interruptEndsTake() throws Exception {
        LatestFrameSlot<String> slot = new LatestFrameSlot<>();
        AtomicBoolean returned = new AtomicBoolean();
        Thread consumer = new Thread(() -> {
            slot.take();
            returned.set(Thread.currentThread().isInterrupted());
        });
        consumer.start();
        Thread.sleep(20);
        consumer.interrupt();
        consumer.join(2000);
        assertTrue(returned.get());
    }
}
