package mirror;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static mirror.MirrorConfig.OnFailure.RECONSUME;
import static mirror.MirrorConfig.OnFailure.SKIP;
import static mirror.MirrorConfig.SendMode.ASYNC;
import static mirror.MirrorConfig.SendMode.ONEWAY;
import static mirror.MirrorConfig.SendMode.SYNC;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageMirroringListenerTest {

    @Mock
    private DefaultMQProducer producer;

    // ---------- rebuild / loopback 纯函数测试 ----------

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

    // ---------- SYNC 模式 ----------

    @Test
    void sync_success_returnsSuccessAndCountsMirror() throws Exception {
        when(producer.send(any(Message.class))).thenReturn(sendResult("new-1"));
        MirrorMetrics metrics = new MirrorMetrics();
        MessageMirroringListener l = new MessageMirroringListener(producer, config(SYNC, RECONSUME), metrics);

        ConsumeConcurrentlyStatus s = l.consumeMessage(List.of(msg("t", "m1")), null);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, s);
        assertTrue(metrics.toPrometheusText().contains("mqmirror_mirrored_total{result=\"success\"} 1"));
        verify(producer).send(any(Message.class));
        verify(producer, never()).sendOneway(any(Message.class));
    }

    @Test
    void sync_failureWithReconsume_returnsReconsumeLater() throws Exception {
        when(producer.send(any(Message.class))).thenThrow(new RemotingException("boom"));
        MirrorMetrics metrics = new MirrorMetrics();
        MessageMirroringListener l = new MessageMirroringListener(producer, config(SYNC, RECONSUME), metrics);

        ConsumeConcurrentlyStatus s = l.consumeMessage(List.of(msg("t", "m1")), null);

        assertEquals(ConsumeConcurrentlyStatus.RECONSUME_LATER, s);
        assertTrue(metrics.toPrometheusText().contains("mqmirror_mirrored_total{result=\"failed\"} 1"));
    }

    @Test
    void sync_failureWithSkip_returnsSuccess() throws Exception {
        when(producer.send(any(Message.class))).thenThrow(new RemotingException("boom"));
        MessageMirroringListener l = new MessageMirroringListener(producer, config(SYNC, SKIP), new MirrorMetrics());

        ConsumeConcurrentlyStatus s = l.consumeMessage(List.of(msg("t", "m1")), null);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, s);
    }

    // 注:ON_FAILURE=halt 会调用 Runtime.halt(2) 直接杀 JVM,无法在单测里验证,集成测试覆盖。

    // ---------- ASYNC 模式 ----------

    @Test
    void async_submitOptimisticallyAcks_callbackSuccessCountsLater() throws Exception {
        ArgumentCaptor<SendCallback> cb = ArgumentCaptor.forClass(SendCallback.class);
        doNothing().when(producer).send(any(Message.class), cb.capture());
        MirrorMetrics metrics = new MirrorMetrics();
        MessageMirroringListener l = new MessageMirroringListener(producer, config(ASYNC, SKIP), metrics);

        ConsumeConcurrentlyStatus s = l.consumeMessage(List.of(msg("t", "m1")), null);

        // 提交即乐观 ACK
        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, s);
        // callback 还没触发时,success 计数仍为 0
        assertFalse(metrics.toPrometheusText().contains("mqmirror_mirrored_total{result=\"success\"} 1"));
        // 模拟 broker 回 ACK
        cb.getValue().onSuccess(sendResult("new-1"));
        assertTrue(metrics.toPrometheusText().contains("mqmirror_mirrored_total{result=\"success\"} 1"));
        verify(producer).send(any(Message.class), any(SendCallback.class));
    }

    @Test
    void async_callbackFailure_countsFailedButStillAcked() throws Exception {
        ArgumentCaptor<SendCallback> cb = ArgumentCaptor.forClass(SendCallback.class);
        doNothing().when(producer).send(any(Message.class), cb.capture());
        MirrorMetrics metrics = new MirrorMetrics();
        // 注意:即便 ON_FAILURE=reconsume,async 回调失败也无法重投
        MessageMirroringListener l = new MessageMirroringListener(producer, config(ASYNC, RECONSUME), metrics);

        ConsumeConcurrentlyStatus s = l.consumeMessage(List.of(msg("t", "m1")), null);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, s);
        // 模拟 broker 回失败
        cb.getValue().onException(new RuntimeException("boom"));
        assertTrue(metrics.toPrometheusText().contains("mqmirror_mirrored_total{result=\"failed\"} 1"));
    }

    @Test
    void async_loopbackMessage_isSkippedNotSent() throws Exception {
        MessageExt m = msg("t", "m1");
        m.putUserProperty(MessageMirroringListener.SOURCE_CLUSTER_PROPERTY, "self");
        MessageMirroringListener l = new MessageMirroringListener(producer, config(ASYNC, SKIP), new MirrorMetrics());

        ConsumeConcurrentlyStatus s = l.consumeMessage(List.of(m), null);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, s);
        verify(producer, never()).send(any(Message.class), any(SendCallback.class));
    }

    // ---------- ONEWAY 模式 ----------

    @Test
    void oneway_callsSendOnewayAndCountsSuccess() throws Exception {
        MirrorMetrics metrics = new MirrorMetrics();
        MessageMirroringListener l = new MessageMirroringListener(producer, config(ONEWAY, SKIP), metrics);

        ConsumeConcurrentlyStatus s = l.consumeMessage(List.of(msg("t", "m1")), null);

        assertEquals(ConsumeConcurrentlyStatus.CONSUME_SUCCESS, s);
        verify(producer).sendOneway(any(Message.class));
        verify(producer, never()).send(any(Message.class));
        assertTrue(metrics.toPrometheusText().contains("mqmirror_mirrored_total{result=\"success\"} 1"));
    }

    // ---------- helpers ----------

    private MirrorConfig config(MirrorConfig.SendMode sm, MirrorConfig.OnFailure of) {
        return new MirrorConfig(
            "rmq", "local", "cg", "pg", 30, null,
            MirrorConfig.ConsumeFrom.LAST, 0, of, true, "self", 0, sm);
    }

    private MessageExt msg(String topic, String id) {
        MessageExt m = new MessageExt();
        m.setTopic(topic);
        m.setBody(new byte[]{1, 2, 3});
        m.setMsgId(id);
        return m;
    }

    private SendResult sendResult(String id) {
        SendResult sr = new SendResult();
        sr.setMsgId(id);
        return sr;
    }
}
