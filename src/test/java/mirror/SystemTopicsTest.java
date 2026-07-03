package mirror;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemTopicsTest {

    @Test
    void nullAndEmptyAreSystem() {
        assertTrue(SystemTopics.isSystem(null));
        assertTrue(SystemTopics.isSystem(""));
    }

    @Test
    void wellKnownSystemTopicsDetected() {
        assertTrue(SystemTopics.isSystem("SCHEDULE_TOPIC_XXXX"));
        assertTrue(SystemTopics.isSystem("rmq_sys_TOPIC"));
        assertTrue(SystemTopics.isSystem("RMQ_SYS_TRANS_HALF_TOPIC"));
        assertTrue(SystemTopics.isSystem("DefaultCluster"));
        assertTrue(SystemTopics.isSystem("DefaultCluster_SCHEDULE_TOPIC"));
        assertTrue(SystemTopics.isSystem("TRANSFER_TOPIC"));
        assertTrue(SystemTopics.isSystem("rocketmq_benchmark_x"));
        assertTrue(SystemTopics.isSystem("%RETRY%my-consumer-group"));
        assertTrue(SystemTopics.isSystem("%DLQ%my-consumer-group"));
        assertTrue(SystemTopics.isSystem("TBW102"));
        assertTrue(SystemTopics.isSystem("OFFSET_MOVED_EVENT"));
        assertTrue(SystemTopics.isSystem("OFFSET_MOVED_EVENT_BROADCAST"));
        assertTrue(SystemTopics.isSystem("RMQ_SYS_TRACE_TOPIC"));
        assertTrue(SystemTopics.isSystem("self_test_topic"));
    }

    @Test
    void businessTopicsNotSystem() {
        assertFalse(SystemTopics.isSystem("p2_v1_patrol_alarm"));
        assertFalse(SystemTopics.isSystem("order-paid"));
        assertFalse(SystemTopics.isSystem("ORDER_CREATED"));
        assertFalse(SystemTopics.isSystem("topic_with_lowercase_and_digits_123"));
    }

    @Test
    void edgeCases() {
        // RMQ_SYS_ 前缀判断:任何以这个前缀开头的都视为系统 topic
        assertTrue(SystemTopics.isSystem("RMQ_SYS_TRANS_HALF_TOPIC"));
        assertTrue(SystemTopics.isSystem("rmq_sys_anything"));
        // 完全无关的字符串应放行
        assertFalse(SystemTopics.isSystem("rmq_something_else"));
        // 注意: startsWith("DefaultCluster") 是前缀匹配,
        // 所以 "DefaultClusterXxx" 也会被判为系统 topic,这是已知行为。
        assertTrue(SystemTopics.isSystem("DefaultCluster_TEST"));
        // 真正非业务的边界用例
        assertFalse(SystemTopics.isSystem("DefaultSomething"));
        assertFalse(SystemTopics.isSystem("just-a-topic"));
    }
}
