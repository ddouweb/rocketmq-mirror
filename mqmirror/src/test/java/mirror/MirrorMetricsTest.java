package mirror;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MirrorMetricsTest {

    @Test
    void freshMetrics_outputHasCoreLines() {
        MirrorMetrics m = new MirrorMetrics();
        String out = m.toPrometheusText();

        // 必须出现的关键指标
        assertTrue(out.contains("# TYPE mqmirror_consumed_total counter"));
        assertTrue(out.contains("mqmirror_consumed_total 0"));
        assertTrue(out.contains("mqmirror_mirrored_total{result=\"success\"} 0"));
        assertTrue(out.contains("mqmirror_mirrored_total{result=\"failed\"} 0"));
        assertTrue(out.contains("mqmirror_uptime_seconds"));
        assertTrue(out.contains("mqmirror_active_topics{kind=\"subscribed\"} 0"));
    }

    @Test
    void increments_appearInOutput() {
        MirrorMetrics m = new MirrorMetrics();
        m.incConsumed();
        m.incConsumed();
        m.incMirroredSuccess();
        m.incMirroredFailed();
        m.incMirroredFailed();
        m.addBytes(128);
        m.setSubscribedTopics(7);
        m.setDiscoveredTopics(9);
        m.recordSendDuration(1_500_000L);  // 1.5 ms

        String out = m.toPrometheusText();

        assertTrue(out.contains("mqmirror_consumed_total 2"));
        assertTrue(out.contains("mqmirror_mirrored_total{result=\"success\"} 1"));
        assertTrue(out.contains("mqmirror_mirrored_total{result=\"failed\"} 2"));
        assertTrue(out.contains("mqmirror_bytes_total 128"));
        assertTrue(out.contains("mqmirror_active_topics{kind=\"subscribed\"} 7"));
        assertTrue(out.contains("mqmirror_active_topics{kind=\"discovered\"} 9"));
        // 1.5ms = 0.0015s,容错检查 substring
        assertTrue(out.contains("mqmirror_send_duration_seconds "));
    }

    @Test
    void loopbackCounter_increments() {
        MirrorMetrics m = new MirrorMetrics();
        m.incLoopbackSkipped();
        m.incLoopbackSkipped();
        m.incLoopbackSkipped();

        assertTrue(m.toPrometheusText().contains("mqmirror_loopback_skipped_total 3"));
    }

    @Test
    void uptimeIsNonNegative() {
        MirrorMetrics m = new MirrorMetrics();
        assertTrue(m.uptimeSeconds() >= 0);
    }

    @Test
    void avgSendDuration_zeroWhenNoSamples() {
        MirrorMetrics m = new MirrorMetrics();
        // 没采样时应输出 0,不产生 NaN/Infinity
        String out = m.toPrometheusText();
        assertTrue(out.contains("mqmirror_send_duration_seconds_avg 0.000000"));
    }

    @Test
    void avgSendDuration_calculatedFromSamples() {
        MirrorMetrics m = new MirrorMetrics();
        m.recordSendDuration(2_000_000_000L); // 2s
        m.recordSendDuration(4_000_000_000L); // 4s
        assertTrue(m.toPrometheusText().contains("mqmirror_send_duration_seconds_avg 3.000000"));
    }
}
