package mirror;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 线程安全指标统计。字段用于 Prometheus 文本格式输出。
 */
public final class MirrorMetrics {

    private final LongAdder consumedTotal = new LongAdder();
    private final LongAdder mirroredSuccess = new LongAdder();
    private final LongAdder mirroredFailed = new LongAdder();
    private final LongAdder bytesTotal = new LongAdder();
    private final LongAdder loopbackSkipped = new LongAdder();
    private final LongAdder subscribeErrors = new LongAdder();
    private final LongAdder sendDurationNanosTotal = new LongAdder();
    private final LongAdder sendDurationCount = new LongAdder();

    private final AtomicInteger subscribedTopics = new AtomicInteger(0);
    private final AtomicInteger discoveredTopics = new AtomicInteger(0);

    private final AtomicLong lastSendDurationNanos = new AtomicLong(0);
    private final long startedAt = System.currentTimeMillis();

    public void incConsumed() { consumedTotal.increment(); }
    public void incMirroredSuccess() { mirroredSuccess.increment(); }
    public void incMirroredFailed() { mirroredFailed.increment(); }
    public void incLoopbackSkipped() { loopbackSkipped.increment(); }
    public void incSubscribeError() { subscribeErrors.increment(); }
    public void addBytes(long n) { bytesTotal.add(n); }

    public void setSubscribedTopics(int n) { subscribedTopics.set(n); }
    public void setDiscoveredTopics(int n) { discoveredTopics.set(n); }

    public void recordSendDuration(long nanos) {
        lastSendDurationNanos.set(nanos);
        sendDurationNanosTotal.add(nanos);
        sendDurationCount.increment();
    }

    public long uptimeSeconds() {
        return Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
    }

    public String toPrometheusText() {
        StringBuilder sb = new StringBuilder(1024);

        writeHelp(sb, "mqmirror_uptime_seconds", "Process uptime in seconds.", "gauge");
        sb.append("mqmirror_uptime_seconds ").append(uptimeSeconds()).append('\n');

        writeHelp(sb, "mqmirror_consumed_total", "Total messages pulled from remote cluster.", "counter");
        sb.append("mqmirror_consumed_total ").append(consumedTotal.sum()).append('\n');

        writeHelp(sb, "mqmirror_mirrored_total", "Messages successfully mirrored to local cluster.", "counter");
        sb.append("mqmirror_mirrored_total{result=\"success\"} ").append(mirroredSuccess.sum()).append('\n');
        sb.append("mqmirror_mirrored_total{result=\"failed\"} ").append(mirroredFailed.sum()).append('\n');

        writeHelp(sb, "mqmirror_loopback_skipped_total", "Messages skipped because they originated from this cluster.", "counter");
        sb.append("mqmirror_loopback_skipped_total ").append(loopbackSkipped.sum()).append('\n');

        writeHelp(sb, "mqmirror_bytes_total", "Body bytes mirrored.", "counter");
        sb.append("mqmirror_bytes_total ").append(bytesTotal.sum()).append('\n');

        writeHelp(sb, "mqmirror_send_errors_total", "Producer send failures.", "counter");
        sb.append("mqmirror_send_errors_total ").append(mirroredFailed.sum()).append('\n');

        writeHelp(sb, "mqmirror_subscribe_errors_total", "Topic subscribe/unsubscribe failures.", "counter");
        sb.append("mqmirror_subscribe_errors_total ").append(subscribeErrors.sum()).append('\n');

        writeHelp(sb, "mqmirror_active_topics", "Topic counts.", "gauge");
        sb.append("mqmirror_active_topics{kind=\"subscribed\"} ").append(subscribedTopics.get()).append('\n');
        sb.append("mqmirror_active_topics{kind=\"discovered\"} ").append(discoveredTopics.get()).append('\n');

        writeHelp(sb, "mqmirror_send_duration_seconds", "Last producer send latency in seconds.", "gauge");
        sb.append("mqmirror_send_duration_seconds ").append(nanosToSeconds(lastSendDurationNanos.get())).append('\n');

        writeHelp(sb, "mqmirror_send_duration_seconds_avg", "Average producer send latency in seconds.", "gauge");
        long cnt = sendDurationCount.sum();
        double avg = cnt == 0 ? 0.0 : nanosToSeconds(sendDurationNanosTotal.sum()) / cnt;
        sb.append("mqmirror_send_duration_seconds_avg ").append(String.format(java.util.Locale.ROOT, "%.6f", avg)).append('\n');

        return sb.toString();
    }

    private static void writeHelp(StringBuilder sb, String name, String help, String type) {
        sb.append("# HELP ").append(name).append(' ').append(help).append('\n');
        sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static double nanosToSeconds(long nanos) {
        return nanos / 1_000_000_000.0;
    }
}
