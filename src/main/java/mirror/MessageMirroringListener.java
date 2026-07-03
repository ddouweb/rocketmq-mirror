package mirror;

import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageConst;
import org.apache.rocketmq.common.message.MessageExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 消息消费 + 转发核心逻辑。
 *
 * 防环:转发消息时打上 SOURCE_CLUSTER_PROPERTY,值取自 config.clusterId。
 *      消费时若该 property 与自身 clusterId 相等则跳过(双向同步时不会形成风暴)。
 */
public final class MessageMirroringListener implements MessageListenerConcurrently {

    private static final Logger log = LoggerFactory.getLogger(MessageMirroringListener.class);

    /** 打在转发出去的消息上,标识消息来自哪个 mqmirror 集群。 */
    public static final String SOURCE_CLUSTER_PROPERTY = "__mqmirror.cluster";

    private final DefaultMQProducer producer;
    private final MirrorConfig config;
    private final MirrorMetrics metrics;

    public MessageMirroringListener(DefaultMQProducer producer,
                                    MirrorConfig config,
                                    MirrorMetrics metrics) {
        this.producer = producer;
        this.config = config;
        this.metrics = metrics;
    }

    @Override
    public ConsumeConcurrentlyStatus consumeMessage(List<MessageExt> msgs, ConsumeConcurrentlyContext ctx) {
        boolean anyFailed = false;

        for (MessageExt m : msgs) {
            metrics.incConsumed();

            if (config.loopPrevention && isLoopback(m, config.clusterId)) {
                metrics.incLoopbackSkipped();
                log.debug("skip loopback message topic={} msgId={}", m.getTopic(), m.getMsgId());
                continue;
            }

            try {
                Message out = rebuild(m, config.clusterId);
                long t0 = System.nanoTime();
                SendResult sr = producer.send(out);
                long dt = System.nanoTime() - t0;
                metrics.recordSendDuration(dt);
                metrics.incMirroredSuccess();
                int bodyLen = m.getBody() == null ? 0 : m.getBody().length;
                metrics.addBytes(bodyLen);
                log.info("mirrored topic={} origMsgId={} -> newMsgId={} in {}ms ({} bytes)",
                    m.getTopic(), m.getMsgId(), sr.getMsgId(),
                    dt / 1_000_000L, bodyLen);
            } catch (Exception e) {
                anyFailed = true;
                metrics.incMirroredFailed();
                log.error("mirror failed topic={} msgId={}", m.getTopic(), m.getMsgId(), e);
            }
        }

        if (!anyFailed) {
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }
        switch (config.onFailure) {
            case SKIP:
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            case HALT:
                log.error("ON_FAILURE=halt triggered, exiting to avoid silent data drift");
                Runtime.getRuntime().halt(2);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            case RECONSUME:
            default:
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        }
    }

    /** 是否为本集群转出去又被镜像回来的环回消息。 */
    static boolean isLoopback(MessageExt m, String clusterId) {
        String origin = m.getUserProperty(SOURCE_CLUSTER_PROPERTY);
        return origin != null && origin.equals(clusterId);
    }

    /** 复制消息体 + 业务 property,过滤掉 RocketMQ 系统保留 property,并打上源集群标识。 */
    static Message rebuild(MessageExt m, String clusterId) {
        Message out = new Message(m.getTopic(), m.getTags(), m.getKeys(), m.getBody());
        Map<String, String> props = m.getProperties();
        if (props != null) {
            for (Map.Entry<String, String> e : props.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                if (k == null || v == null) continue;
                if (MessageConst.STRING_HASH_SET.contains(k)) continue;
                if (SOURCE_CLUSTER_PROPERTY.equals(k)) continue;
                try {
                    out.putUserProperty(k, v);
                } catch (RuntimeException ignore) {
                    // 跳过其他系统保留 property
                }
            }
        }
        try {
            out.putUserProperty(SOURCE_CLUSTER_PROPERTY, clusterId);
        } catch (RuntimeException ignore) {
            // 极少数情况下 clusterId 含非法字符,忽略
        }
        return out;
    }
}
