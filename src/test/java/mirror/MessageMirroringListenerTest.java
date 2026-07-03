package mirror;

import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageMirroringListenerTest {

    @Test
    void rebuild_copiesTopicTagKeysBody() {
        MessageExt src = new MessageExt();
        src.setTopic("order-paid");
        src.setTags("vip");
        src.setKeys("order-123");
        src.setBody(new byte[]{1, 2, 3});

        Message out = MessageMirroringListener.rebuild(src, "cluster-a");

        assertEquals("order-paid", out.getTopic());
        assertEquals("vip", out.getTags());
        assertEquals("order-123", out.getKeys());
        assertEquals(3, out.getBody().length);
    }

    @Test
    void rebuild_stampsSourceClusterProperty() {
        MessageExt src = new MessageExt();
        src.setTopic("t");

        Message out = MessageMirroringListener.rebuild(src, "prod-cn");

        assertEquals("prod-cn", out.getUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY));
    }

    @Test
    void rebuild_overwritesExistingSourceClusterProperty() {
        // 防止外部伪造的 cluster id 透传造成混乱
        MessageExt src = new MessageExt();
        src.setTopic("t");
        src.putUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY, "external-spoof");

        Message out = MessageMirroringListener.rebuild(src, "self");

        assertEquals("self", out.getUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY));
    }

    @Test
    void rebuild_preservesBusinessProperty() {
        MessageExt src = new MessageExt();
        src.setTopic("t");
        src.putUserProperty("trace-id", "abc-xyz");

        Message out = MessageMirroringListener.rebuild(src, "self");

        assertEquals("abc-xyz", out.getUserProperty("trace-id"));
    }

    @Test
    void rebuild_incomingSourceClusterPropertyDoesNotDuplicate() {
        // 上游 mqmirror 传过来的 cluster property 应被本集群覆盖,而不是再次叠加
        MessageExt src = new MessageExt();
        src.setTopic("t");
        src.putUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY, "upstream");

        Message out = MessageMirroringListener.rebuild(src, "self");

        // 仍是单一 property,值是自身
        assertEquals("self", out.getUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY));
    }

    @Test
    void isLoopback_matchesOwnClusterId() {
        MessageExt m = new MessageExt();
        m.putUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY, "self");
        assertTrue(MessageMirroringListener.isLoopback(m, "self"));
    }

    @Test
    void isLoopback_doesNotMatchDifferentCluster() {
        MessageExt m = new MessageExt();
        m.putUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY, "other");
        assertFalse(MessageMirroringListener.isLoopback(m, "self"));
    }

    @Test
    void isLoopback_missingPropertyIsNotLoopback() {
        // 上游业务消息(没经过 mqmirror)不会被误判为环回
        MessageExt m = new MessageExt();
        assertFalse(MessageMirroringListener.isLoopback(m, "self"));
    }
}
