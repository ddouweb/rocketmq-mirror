package mirror;

import org.junit.jupiter.api.Test;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopicSynchronizerDiffTest {

    @Test
    void diff_emptySetsProduceEmptyResult() {
        TopicSynchronizer.DiffResult d =
            TopicSynchronizer.diff(Collections.emptySet(), Collections.emptySet());
        assertTrue(d.toAdd.isEmpty());
        assertTrue(d.toRemove.isEmpty());
    }

    @Test
    void diff_newTopicBecomesToAdd() {
        Set<String> desired = setOf("a", "b", "c");
        Set<String> current = setOf("a", "b");

        TopicSynchronizer.DiffResult d = TopicSynchronizer.diff(desired, current);

        assertEquals(setOf("c"), d.toAdd);
        assertTrue(d.toRemove.isEmpty());
    }

    @Test
    void diff_disappearedTopicBecomesToRemove() {
        Set<String> desired = setOf("a");
        Set<String> current = setOf("a", "b", "c");

        TopicSynchronizer.DiffResult d = TopicSynchronizer.diff(desired, current);

        assertTrue(d.toAdd.isEmpty());
        assertEquals(setOf("b", "c"), d.toRemove);
    }

    @Test
    void diff_simultaneousAddAndRemove() {
        Set<String> desired = setOf("a", "b", "d");
        Set<String> current = setOf("a", "b", "c");

        TopicSynchronizer.DiffResult d = TopicSynchronizer.diff(desired, current);

        assertEquals(setOf("d"), d.toAdd);
        assertEquals(setOf("c"), d.toRemove);
    }

    @Test
    void diff_doesNotMutateInputs() {
        Set<String> desired = new HashSet<>(setOf("a", "b"));
        Set<String> current = new HashSet<>(setOf("b", "c"));

        TopicSynchronizer.diff(desired, current);

        // 验证输入集合未被修改
        assertEquals(2, desired.size());
        assertTrue(desired.contains("a"));
        assertTrue(desired.contains("b"));
        assertEquals(2, current.size());
        assertTrue(current.contains("b"));
        assertTrue(current.contains("c"));
    }

    private static Set<String> setOf(String... s) {
        Set<String> r = new HashSet<>();
        for (String x : s) r.add(x);
        return r;
    }
}
