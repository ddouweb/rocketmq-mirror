package mirror;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.protocol.heartbeat.MessageModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mqmirror 主入口。
 *
 * 用法:
 *   java -jar mqmirror.jar
 *   (从环境变量读取配置,见 MirrorConfig 文档)
 */
public final class MirrorApp {

    private static final Logger log = LoggerFactory.getLogger(MirrorApp.class);

    private MirrorApp() {}

    public static void main(String[] args) throws Exception {
        MirrorConfig config = MirrorConfig.fromEnv();
        log.info("starting with: {}", config);

        MirrorMetrics metrics = new MirrorMetrics();
        PrometheusExporter exporter = new PrometheusExporter(metrics, config.metricsPort);
        exporter.start();
        if (config.metricsPort > 0) {
            log.info("metrics endpoint on :{}/metrics", config.metricsPort);
        }

        DefaultMQProducer producer = new DefaultMQProducer(config.producerGroup);
        producer.setNamesrvAddr(config.localNs);
        producer.start();
        log.info("producer started, ns={} group={}", config.localNs, config.producerGroup);

        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer(config.consumerGroup);
        consumer.setNamesrvAddr(config.remoteNs);
        consumer.setMessageModel(MessageModel.CLUSTERING);
        applyConsumeFrom(consumer, config);
        consumer.registerMessageListener(new MessageMirroringListener(producer, config, metrics));
        consumer.start();
        log.info("consumer started, ns={} group={}", config.remoteNs, config.consumerGroup);

        TopicSynchronizer sync = new TopicSynchronizer(
            consumer, config.remoteNs, config.whitelist, config.refreshSec, metrics);
        sync.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("shutting down...");
            sync.shutdown();
            try { consumer.shutdown(); } catch (Exception ignore) {}
            try { producer.shutdown(); } catch (Exception ignore) {}
            try { exporter.stop(); } catch (Exception ignore) {}
        }, "shutdown-hook"));

        log.info("mqmirror is running. clusterId={} onFailure={} consumeFrom={} loopPrevention={}",
            config.clusterId, config.onFailure, config.consumeFrom, config.loopPrevention);
        Thread.currentThread().join();
    }

    private static void applyConsumeFrom(DefaultMQPushConsumer consumer, MirrorConfig config) {
        switch (config.consumeFrom) {
            case FIRST:
                consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
                break;
            case TIMESTAMP:
                consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_TIMESTAMP);
                consumer.setConsumeTimestamp(String.valueOf(config.consumeTimestamp));
                break;
            case STORED:
            case LAST:
            default:
                consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_LAST_OFFSET);
                break;
        }
    }
}
