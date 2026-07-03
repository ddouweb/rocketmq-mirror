package mirror;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * mqmirror 运行配置。所有字段从环境变量读取,带合理默认值。
 *
 * 必填:
 *   - REMOTE_NAMESRV  远端(源)集群 nameserver 地址
 *
 * 集群身份 / 防环:
 *   - CLUSTER_ID      本集群标识,会打在转发消息 property 上,默认 "default"
 *   - LOOP_PREVENTION 是否启用防环检测,默认 true
 *
 * 消费语义:
 *   - CONSUME_FROM    last | first | timestamp | stored,默认 last
 *   - CONSUME_TIMESTAMP 当 CONSUME_FROM=timestamp 时使用,毫秒时间戳
 *   - ON_FAILURE      reconsume | skip | halt,默认 reconsume
 *
 * 同步策略:
 *   - TOPIC_WHITELIST 逗号分隔 topic 列表,设了只镜像这些;不设则镜像所有业务 topic
 *   - REFRESH_SEC     topic 列表刷新周期,秒,默认 30
 *
 * 监控:
 *   - METRICS_PORT    Prometheus metrics HTTP 端口,默认 9100;设为 0 关闭
 */
public final class MirrorConfig {

    public final String remoteNs;
    public final String localNs;
    public final String consumerGroup;
    public final String producerGroup;
    public final long refreshSec;
    public final Set<String> whitelist;
    public final ConsumeFrom consumeFrom;
    public final long consumeTimestamp;
    public final OnFailure onFailure;
    public final boolean loopPrevention;
    public final String clusterId;
    public final int metricsPort;

    public enum ConsumeFrom { LAST, FIRST, TIMESTAMP, STORED }
    public enum OnFailure { RECONSUME, SKIP, HALT }

    public MirrorConfig(
            String remoteNs,
            String localNs,
            String consumerGroup,
            String producerGroup,
            long refreshSec,
            Set<String> whitelist,
            ConsumeFrom consumeFrom,
            long consumeTimestamp,
            OnFailure onFailure,
            boolean loopPrevention,
            String clusterId,
            int metricsPort) {
        this.remoteNs = remoteNs;
        this.localNs = localNs;
        this.consumerGroup = consumerGroup;
        this.producerGroup = producerGroup;
        this.refreshSec = refreshSec;
        this.whitelist = whitelist;
        this.consumeFrom = consumeFrom;
        this.consumeTimestamp = consumeTimestamp;
        this.onFailure = onFailure;
        this.loopPrevention = loopPrevention;
        this.clusterId = clusterId;
        this.metricsPort = metricsPort;
    }

    public static MirrorConfig fromEnv() {
        String remoteNs = required("REMOTE_NAMESRV");
        String localNs = env("LOCAL_NAMESRV", "namesrv:9876");
        String cGroup = env("CONSUMER_GROUP", "mirror-consumer");
        String pGroup = env("PRODUCER_GROUP", "mirror-producer");
        long refreshSec = Long.parseLong(env("REFRESH_SEC", "30"));
        Set<String> whitelist = parseWhitelist(env("TOPIC_WHITELIST", ""));
        ConsumeFrom consumeFrom = ConsumeFrom.valueOf(
            env("CONSUME_FROM", "LAST").toUpperCase());
        long consumeTimestamp = Long.parseLong(env("CONSUME_TIMESTAMP", "0"));
        OnFailure onFailure = OnFailure.valueOf(
            env("ON_FAILURE", "RECONSUME").toUpperCase());
        boolean loopPrevention = Boolean.parseBoolean(env("LOOP_PREVENTION", "true"));
        String clusterId = env("CLUSTER_ID", "default");
        int metricsPort = Integer.parseInt(env("METRICS_PORT", "9100"));

        return new MirrorConfig(
            remoteNs, localNs, cGroup, pGroup, refreshSec, whitelist,
            consumeFrom, consumeTimestamp, onFailure,
            loopPrevention, clusterId, metricsPort);
    }

    static Set<String> parseWhitelist(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        return Arrays.stream(raw.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(HashSet::new));
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static String required(String key) {
        String v = System.getenv(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing required env: " + key);
        }
        return v;
    }

    @Override
    public String toString() {
        return "MirrorConfig{" +
            "remoteNs='" + remoteNs + '\'' +
            ", localNs='" + localNs + '\'' +
            ", consumerGroup='" + consumerGroup + '\'' +
            ", producerGroup='" + producerGroup + '\'' +
            ", refreshSec=" + refreshSec +
            ", whitelist=" + whitelist +
            ", consumeFrom=" + consumeFrom +
            ", consumeTimestamp=" + consumeTimestamp +
            ", onFailure=" + onFailure +
            ", loopPrevention=" + loopPrevention +
            ", clusterId='" + clusterId + '\'' +
            ", metricsPort=" + metricsPort +
            '}';
    }
}
