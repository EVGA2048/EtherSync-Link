package com.etherstories.link;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用内存账本复现当前 {@link ChestNet} 发送/接收时序的崩溃窗口。
 * 这些用例先钉住「现在会重复投递」的事实；下一步的 transfer ID 状态机要让它们反过来。
 */
class DeliveryCrashWindowTest {

    enum Place { TX, QUEUE, RX }

    @Test
    void crashAfterEnqueueBeforeTxRemoveDuplicatesOnRetry() {
        World world = World.withItemInTx("create:package");
        world.enqueueCurrent();
        assertEquals(1, world.count("create:package", Place.TX), "崩溃时物品还在发送箱");
        assertEquals(1, world.count("create:package", Place.QUEUE));

        world.retrySendFromChest();

        assertEquals(2, world.count("create:package", Place.QUEUE),
                "当前 takeMany 先 enqueueBatch 再删源物品：崩溃后重试会再入队一次");
    }

    @Test
    void crashAfterRxInsertBeforeDeliveredDuplicatesOnRetry() {
        World world = World.withQueued("minecraft:shulker_box");
        world.placeIntoRx();
        World crashed = world.snapshot();

        crashed.retryDeliverPending();

        assertEquals(2, crashed.count("minecraft:shulker_box", Place.RX),
                "当前 deliver 先放入 RX 再异步 mark delivered：崩溃后同一 pending 会再投一次");
        assertEquals(1, crashed.pendingCount());
    }

    @Test
    void desiredRecoveryKeepsASingleCopy() {
        TransferBook book = new TransferBook();
        String sendId = book.reserve("create:package");
        book.markQueued(sendId);
        book.crashBeforeSourceRemove(sendId);
        book.recover();
        assertEquals(1, book.copies("create:package"));
        assertEquals("SOURCE", book.phase(sendId));

        String recvId = book.reserve("minecraft:shulker_box");
        book.markQueued(recvId);
        book.markRemovedFromSource(recvId);
        book.markPlaced(recvId);
        book.crashBeforeConfirm(recvId);
        book.recover();
        assertEquals(1, book.copies("minecraft:shulker_box"));
        assertEquals("CONFIRMED", book.phase(recvId));
    }

    /** 按当前 ChestNet 顺序推进的粗糙世界，只用于暴露重复窗口。 */
    private static final class World {
        private final List<String> tx = new ArrayList<>();
        private final List<String> queue = new ArrayList<>();
        private final List<String> rx = new ArrayList<>();
        private final List<String> pending = new ArrayList<>();

        static World withItemInTx(String key) {
            World w = new World();
            w.tx.add(key);
            return w;
        }

        static World withQueued(String key) {
            World w = new World();
            w.queue.add(key);
            w.pending.add(key);
            return w;
        }

        void enqueueCurrent() {
            for (String key : List.copyOf(tx)) {
                queue.add(key);
                pending.add(key);
            }
        }

        void retrySendFromChest() {
            enqueueCurrent();
            tx.clear();
        }

        void placeIntoRx() {
            for (String key : List.copyOf(pending)) {
                rx.add(key);
            }
        }

        void retryDeliverPending() {
            placeIntoRx();
        }

        int count(String key, Place place) {
            List<String> src = switch (place) {
                case TX -> tx;
                case QUEUE -> queue;
                case RX -> rx;
            };
            return (int) src.stream().filter(key::equals).count();
        }

        int pendingCount() {
            return pending.size();
        }

        World snapshot() {
            World copy = new World();
            copy.tx.addAll(tx);
            copy.queue.addAll(queue);
            copy.rx.addAll(rx);
            copy.pending.addAll(pending);
            return copy;
        }
    }

    /** 下一步要落到生产代码里的目标账本：同一 transfer 只对应一份物品。 */
    private static final class TransferBook {
        private final Map<String, String> phase = new LinkedHashMap<>();
        private final Map<String, String> item = new LinkedHashMap<>();
        private final Map<String, Integer> copies = new LinkedHashMap<>();
        private int seq;

        String reserve(String key) {
            String id = "t" + (++seq);
            phase.put(id, "RESERVED");
            item.put(id, key);
            copies.merge(key, 1, Integer::sum);
            return id;
        }

        void markQueued(String id) {
            phase.put(id, "QUEUED");
        }

        void crashBeforeSourceRemove(String id) {
            phase.put(id, "QUEUED_STILL_IN_SOURCE");
        }

        void markRemovedFromSource(String id) {
            phase.put(id, "IN_FLIGHT");
        }

        void markPlaced(String id) {
            phase.put(id, "PLACED");
        }

        void crashBeforeConfirm(String id) {
            phase.put(id, "PLACED_UNCONFIRMED");
        }

        void recover() {
            for (var e : List.copyOf(phase.entrySet())) {
                switch (e.getValue()) {
                    case "QUEUED_STILL_IN_SOURCE", "RESERVED" -> phase.put(e.getKey(), "SOURCE");
                    case "PLACED", "PLACED_UNCONFIRMED" -> phase.put(e.getKey(), "CONFIRMED");
                    default -> {
                    }
                }
            }
        }

        String phase(String id) {
            return phase.get(id);
        }

        int copies(String key) {
            return copies.getOrDefault(key, 0);
        }
    }
}
