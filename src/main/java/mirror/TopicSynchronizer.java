package mirror;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.common.protocol.body.TopicList;
import org.apache.rocketmq.tools.admin.DefaultMQAdminExt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 周期性同步远端 topic 列表,自动 subscribe / unsubscribe。
 * 白名单模式直接用配置,全量模式则拉取 nameserver。
 */
public final class TopicSynchronizer {

    private static final Logger log = LoggerFactory.getLogger(TopicSynchronizer.class);

    private final DefaultMQPushConsumer consumer;
    private final String remoteNs;
    private final Set<String> whitelist;
    private final long refreshSec;
    private final MirrorMetrics metrics;

    private final Set<String> subscribed = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;

    public TopicSynchronizer(DefaultMQPushConsumer consumer,
                             String remoteNs,
                             Set<String> whitelist,
                             long refreshSec,
                             MirrorMetrics metrics) {
        this.consumer = consumer;
        this.remoteNs = remoteNs;
        this.whitelist = whitelist;
        this.refreshSec = refreshSec;
        this.metrics = metrics;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "topic-sync");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(
            this::syncOnce, 0, refreshSec, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    /** 单次同步,可被测试直接调用。 */
    public void syncOnce() {
        Set<String> desired = computeDesired();
        if (desired == null) return;

        metrics.setDiscoveredTopics(desired.size());

        DiffResult diff = diff(desired, subscribed);
        for (String t : diff.toAdd) {
            try {
                consumer.subscribe(t, "*");
                subscribed.add(t);
                log.info("subscribed: {} (total={})", t, subscribed.size());
            } catch (Exception e) {
                metrics.incSubscribeError();
                log.error("subscribe failed: {}", t, e);
            }
        }
        for (String t : diff.toRemove) {
            try {
                consumer.unsubscribe(t);
                subscribed.remove(t);
                log.info("unsubscribed: {} (total={})", t, subscribed.size());
            } catch (Exception e) {
                metrics.incSubscribeError();
                log.error("unsubscribe failed: {}", t, e);
            }
        }

        metrics.setSubscribedTopics(subscribed.size());
    }

    private Set<String> computeDesired() {
        if (whitelist != null) {
            return new HashSet<>(whitelist);
        }
        DefaultMQAdminExt admin = null;
        try {
            admin = new DefaultMQAdminExt();
            admin.setNamesrvAddr(remoteNs);
            admin.start();
            TopicList tl = admin.fetchAllTopicList();
            Set<String> all = tl.getTopicList();
            Set<String> filtered = new HashSet<>();
            for (String t : all) {
                if (!SystemTopics.isSystem(t)) filtered.add(t);
            }
            return filtered;
        } catch (Exception e) {
            log.warn("sync topics failed (will retry next cycle): {}", e.getMessage());
            return null;
        } finally {
            if (admin != null) {
                try { admin.shutdown(); } catch (Exception ignore) {}
            }
        }
    }

    public Set<String> snapshotSubscribed() {
        return new HashSet<>(subscribed);
    }

    /** 计算 desired -> current 的差集:toAdd 是 desired 多出来的,toRemove 是 current 多出来的。 */
    public static DiffResult diff(Set<String> desired, Set<String> current) {
        Set<String> toAdd = new HashSet<>(desired);
        toAdd.removeAll(current);
        Set<String> toRemove = new HashSet<>(current);
        toRemove.removeAll(desired);
        return new DiffResult(toAdd, toRemove);
    }

    public static final class DiffResult {
        public final Set<String> toAdd;
        public final Set<String> toRemove;

        public DiffResult(Set<String> toAdd, Set<String> toRemove) {
            this.toAdd = toAdd;
            this.toRemove = toRemove;
        }
    }
}
