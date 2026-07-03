package mirror;

/**
 * RocketMQ 系统 topic 判断。源集群里这些 topic 不应被镜像。
 */
public final class SystemTopics {

    private SystemTopics() {}

    public static boolean isSystem(String name) {
        if (name == null || name.isEmpty()) return true;

        if (name.startsWith("SCHEDULE_TOPIC_")
            || name.startsWith("rmq_sys_")
            || name.startsWith("RMQ_SYS_")
            || name.startsWith("DefaultCluster")
            || name.startsWith("TRANSFER_")
            || name.startsWith("rocketmq_benchmark_")
            || name.startsWith("%RETRY%")
            || name.startsWith("%DLQ%")
            || name.startsWith("OffsetMovedEvent")) {
            return true;
        }

        switch (name) {
            case "TBW102":
            case "OFFSET_MOVED_EVENT":
            case "OFFSET_MOVED_EVENT_BROADCAST":
            case "RMQ_SYS_TRANS_HALF_TOPIC":
            case "RMQ_SYS_TRACE_TOPIC":
            case "BenchmarkTest":
            case "ICON_PROFILE":
            case "zookeeper":
            case "self_test_topic":
            case "DefaultCluster_TEST":
            case "rmq_node":
                return true;
            default:
                return false;
        }
    }
}
