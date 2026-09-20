package org.rafalohaki.wpmecore.addons.skyblock.quests;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyQuestBufferTest {

    @Test
    void failedBatchKeepsItsIdentityAndNewOwnerWaitsBehindIt() {
        DailyQuestService.PendingProgress progress =
                new DailyQuestService.PendingProgress();
        DailyQuestService.QuestPayload first = new DailyQuestService.QuestPayload(
                UUID.fromString("11111111-1111-1111-1111-111111111111"), 10L, 50L);
        DailyQuestService.QuestPayload replacement = new DailyQuestService.QuestPayload(
                UUID.fromString("22222222-2222-2222-2222-222222222222"), 10L, 50L);
        progress.add(first);
        progress.add(first);

        DailyQuestService.ActiveBatch original = progress.nextBatch();
        assertEquals(2L, original.increment());
        assertTrue(progress.shouldLogFailure(original));

        progress.add(replacement);
        DailyQuestService.ActiveBatch retry = progress.nextBatch();
        assertSame(original, retry);
        assertEquals(original.batchId(), retry.batchId());
        assertFalse(progress.shouldLogFailure(retry));

        progress.complete(retry);
        DailyQuestService.ActiveBatch next = progress.nextBatch();
        assertEquals(replacement, next.payload());
        assertEquals(1L, next.increment());
        assertNotEquals(original.batchId(), next.batchId());
        progress.complete(next);
        assertFalse(progress.hasWork());
        assertNull(progress.nextBatch());
    }

    @Test
    void adjacentEventsWithTheSamePayloadAreAggregatedButPayloadChangesAreOrdered() {
        DailyQuestService.PendingProgress progress =
                new DailyQuestService.PendingProgress();
        DailyQuestService.QuestPayload first = new DailyQuestService.QuestPayload(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), 20L, 75L);
        DailyQuestService.QuestPayload changedCatalog = new DailyQuestService.QuestPayload(
                first.ownerId(), 25L, 100L);
        progress.add(first);
        progress.add(first);
        progress.add(changedCatalog);
        progress.add(changedCatalog);

        DailyQuestService.ActiveBatch firstBatch = progress.nextBatch();
        assertEquals(first, firstBatch.payload());
        assertEquals(2L, firstBatch.increment());
        progress.complete(firstBatch);

        DailyQuestService.ActiveBatch secondBatch = progress.nextBatch();
        assertEquals(changedCatalog, secondBatch.payload());
        assertEquals(2L, secondBatch.increment());
    }

    /**
     * Crafting reports a whole batch from one event: shift-clicking a recipe yields a
     * full stack, and Paper's ItemCraftedEvent carries that real amount. Counting one
     * per event would score a stack of 64 as a single craft.
     */
    @Test
    void batchedProgressCountsTheWholeAmountAndMergesWithNeighbours() {
        DailyQuestService.PendingProgress progress =
                new DailyQuestService.PendingProgress();
        DailyQuestService.QuestPayload payload = new DailyQuestService.QuestPayload(
                UUID.fromString("33333333-3333-3333-3333-333333333333"), 64L, 100L);

        progress.add(payload, 8L);
        progress.add(payload, 56L);

        DailyQuestService.ActiveBatch batch = progress.nextBatch();
        assertEquals(64L, batch.increment());
    }

    @Test
    void nonPositiveProgressIsIgnored() {
        DailyQuestService.PendingProgress progress =
                new DailyQuestService.PendingProgress();
        DailyQuestService.QuestPayload payload = new DailyQuestService.QuestPayload(
                UUID.fromString("44444444-4444-4444-4444-444444444444"), 10L, 50L);

        progress.add(payload, 0L);
        progress.add(payload, -5L);

        assertNull(progress.nextBatch());
    }
}
