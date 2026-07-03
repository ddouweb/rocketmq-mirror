# 部署方式

mqmirror 三种典型部署场景,根据网络拓扑选一种。

---

## 场景一:Docker Swarm 跨主机(默认推荐)

**适用**:本地开发机/测试集群要镜像生产 RocketMQ,但生产 broker 报告的 IP 是它自己容器内 IP,本地连不通。

**关键**:`entrypoint.sh` 用 iptables DNAT 把 broker 容器内 IP 重定向到生产机物理 IP。

参考根目录 `swarm.yaml`:

```yaml
mqmirror:
  cap_add:
    - NET_ADMIN   # 必须,否则没权限写 iptables
  environment:
    - REMOTE_NAMESRV=192.168.8.130:9876
    - LOCAL_NAMESRV=namesrv:9876
    - REMOTE_BROKER_CONTAINER_IP=192.168.1.99   # 远端 broker 容器内 IP
    - REMOTE_BROKER_HOST_IP=192.168.8.130        # 远端 broker 物理机 IP
```

启动:
```bash
docker swarm init   # 如未初始化
docker stack deploy -c swarm.yaml rocketmq
```

**多 broker**:用逗号分隔多对 IP,数量必须一致:
```bash
REMOTE_BROKER_CONTAINER_IP=10.0.0.1,10.0.0.2
REMOTE_BROKER_HOST_IP=192.168.8.130,192.168.8.131
```

---

## 场景二:K8s 直连(无 iptables)

**适用**:K8s 集群内部署,远端 RocketMQ 通过 NodePort / LoadBalancer / 外部域名可达,broker 上报的 IP 本身就路由可达。

**关键**:不配 `REMOTE_BROKER_*_IP`,所以 `entrypoint.sh` 跳过 iptables 步骤,直接进 Java。

参考 `k8s.yaml`:
```bash
kubectl apply -f deploy/k8s.yaml
```

如需 Prometheus 抓取,`k8s.yaml` 已带 `ServiceMonitor`(需要 Prometheus Operator)。

**HostNetwork 模式**:某些场景下 broker 用 K8s 节点 IP 注册,这时把 `hostNetwork: true` 加到 Pod spec 即可,不需要 DNAT。

---

## 场景三:本地 docker-compose(单机演示)

**适用**:本机起一套 RocketMQ 做开发,远端 RocketMQ 直接端口映射到本机。

参考根目录 `docker-compose.yaml`,跑起来:
```bash
docker compose up -d
```

构建本地镜像:
```bash
cd mqmirror
mvn -DskipTests package
docker build -t rocketmq-mirror:latest .
```

---

## 双向同步(双集群互备)

部署两个 mqmirror 实例,各负责一个方向,**务必**:
1. 两端 `CLUSTER_ID` 设不同的值
2. 两端 `LOOP_PREVENTION=true`(默认就是)

参考 `swarm-bidirectional.yaml`,mqmirror 会自动识别"消息是否来自自身集群"并跳过,避免消息风暴。

---

## 监控接入

`/metrics` 输出 Prometheus 格式指标,关键指标:

| 指标 | 说明 |
|------|------|
| `mqmirror_consumed_total` | 从远端拉到的消息总数 |
| `mqmirror_mirrored_total{result="success\|failed"}` | 镜像成功/失败数 |
| `mqmirror_active_topics{kind="subscribed\|discovered"}` | 当前订阅/发现的 topic 数 |
| `mqmirror_send_duration_seconds` | 最近一次 producer.send 延迟 |
| `mqmirror_send_duration_seconds_avg` | 平均 send 延迟 |
| `mqmirror_loopback_skipped_total` | 因防环被跳过的消息数(双向同步会有计数) |
| `mqmirror_subscribe_errors_total` | subscribe/unsubscribe 失败次数 |
| `mqmirror_uptime_seconds` | 进程运行时长 |

Grafana 建议:
- 镜像 TPS = rate(mqmirror_mirrored_total{result="success"}[1m])
- 失败率 = rate(mqmirror_mirrored_total{result="failed"}[1m]) / rate(mqmirror_consumed_total[1m])
- 滞后告警 = mqmirror_send_duration_seconds_avg > 1
