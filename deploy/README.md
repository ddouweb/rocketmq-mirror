# 部署方式

> ⚠️ **先读这一段**:mqmirror 的部署难度,90% 取决于「远端 broker 上报的 IP 在你这台机器上能不能直连」。请按下面的决策树先判断你的场景,**避免走死胡同**。

---

## 决策树:5 秒判断你走哪条路

```
你的开发机能 ping 通远端 broker 上报的 IP 吗?
│
├── 能(场景 A) ──> 直接 java -jar,最简单
│                  适合:远端 broker.conf 配了 brokerIP1=可达IP / K8s hostNetwork / 同机房
│                  详见 docs/STANDALONE.md
│
└── 不能(典型:broker 上报容器内 IP)
    │
    ├── 你能在远端 broker.conf 加 brokerIP1=本机可达IP 吗?
    │   ├── 能 ──> 改完变场景 A,直接 java -jar(治本,推荐)
    │   │
    │   └── 不能 ──> 必须用容器,NAT 自动化
    │                ├── Docker Swarm ──> 场景 B(本文档)
    │                ├── Kubernetes    ──> 场景 C(本文档)
    │                └── 本地开发      ──> 场景 D(docker-compose)
    │
    └── 既改不了远端、又不能用容器
        ──> 宿主机 + 手动 NAT(Linux 还行,Win/Mac 很烦)
        详见 docs/STANDALONE.md「不推荐方案」一节
        ──> 这种情况要认真考虑是否值得用本工具
```

---

## 场景 A:直接 `java -jar`(最简单)

**前提**:远端 broker 上报的 IP 在你机器上可达。

**怎么判断**:
```bash
# 1. 拿到 broker 上报的 IP(连 nameserver 看 brokerAddrTable,或在 rocketmq-dashboard 看)
# 2. 直接 telnet / Test-NetConnection 测试
telnet <broker-ip> 10911        # Linux/Mac/Win 都能用
```
能连通 → 走这个场景。

**怎么跑**:

```bash
# Linux / macOS / WSL
export REMOTE_NAMESRV=prod-ns:9876
export LOCAL_NAMESRV=127.0.0.1:9876
export CLUSTER_ID=local-dev
java -jar mqmirror.jar
```

```cmd
:: Windows CMD
set REMOTE_NAMESRV=prod-ns:9876
set LOCAL_NAMESRV=127.0.0.1:9876
set CLUSTER_ID=local-dev
java -jar mqmirror.jar
```

```powershell
# Windows PowerShell
$env:REMOTE_NAMESRV="prod-ns:9876"
$env:LOCAL_NAMESRV="127.0.0.1:9876"
$env:CLUSTER_ID="local-dev"
java -jar mqmirror.jar
```

详细配置项见 [docs/CONFIG.md](../docs/CONFIG.md)。

---

## 场景 B:Docker Swarm 跨主机(iptables 自动)

**适用**:开发机要镜像生产 RocketMQ,但生产 broker 上报的是容器内 IP,本机连不通,**且你没有权限改生产 broker.conf**。

**关键**:`entrypoint.sh` 自动用 iptables DNAT 把 broker 容器内 IP 重定向到物理机 IP。你只需要配两个环境变量,工具自动搞定。

参考根目录 `../swarm.yaml`:

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

## 场景 C:Kubernetes(无需 iptables)

**适用**:K8s 集群内部署,远端 RocketMQ 通过 NodePort / LoadBalancer / 外部域名可达,broker 上报的 IP 本身就路由可达。

**关键**:不配 `REMOTE_BROKER_*_IP`,`entrypoint.sh` 跳过 iptables,直接进 Java。

参考 `k8s.yaml`:
```bash
kubectl apply -f deploy/k8s.yaml
```

`k8s.yaml` 已带 `ServiceMonitor`,Prometheus Operator 自动发现抓取。

**HostNetwork 模式**:某些场景下 broker 用 K8s 节点 IP 注册,这时把 `hostNetwork: true` 加到 Pod spec 即可,不需要 DNAT。

---

## 场景 D:本地 docker-compose(单机演示)

**适用**:本机起一套 RocketMQ 做开发,远端 RocketMQ 直接端口映射到本机,或本机回环测试。

参考根目录 `../docker-compose.yaml`,默认拉 ghcr 官方镜像:
```bash
docker compose up -d
```

想用本地 build 的镜像(多阶段构建,不需要先 mvn package):
```bash
docker build -t ghcr.io/ddouweb/rocketmq-mirror:latest .
docker compose up -d
```

---

## 场景 E:双向同步(双集群互备)

部署两个 mqmirror 实例,各负责一个方向。**务必**:
1. 两端 `CLUSTER_ID` 设不同的值
2. 两端 `LOOP_PREVENTION=true`(默认就是)

参考 `swarm-bidirectional.yaml`。mqmirror 自动识别「消息是否来自自身集群」并跳过,避免消息风暴。

---

## 不推荐:宿主机 + 手动 NAT

如果你既改不了远端 broker 配置、又不能用 Docker,**只能**在宿主机手动配 NAT。这个方案:

- **Linux**:iptables 命令一行,跟容器内一模一样
- **Windows**:需要先加 IP 别名(`netsh interface ipv4 add address`),再加 portproxy,3 步操作
- **macOS**:需要 pfctl 或 socat,更麻烦

详细命令见 [docs/STANDALONE.md](../docs/STANDALONE.md)「不推荐方案」一节。**真的建议你先认真考虑改 broker 配置或用容器**,这条路得不偿失。

---

## 监控接入

`/metrics` 输出 Prometheus 格式指标。关键指标:

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
- 镜像 TPS = `rate(mqmirror_mirrored_total{result="success"}[1m])`
- 失败率 = `rate(mqmirror_mirrored_total{result="failed"}[1m]) / rate(mqmirror_consumed_total[1m])`
- 滞后告警 = `mqmirror_send_duration_seconds_avg > 1`

不想手搭?直接 import 现成的 Grafana 面板,见 [`../grafana/README.md`](../grafana/README.md)。
