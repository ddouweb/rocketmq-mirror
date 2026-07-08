# mqmirror

> Lightweight cross-cluster RocketMQ message mirroring tool — one JAR, one container, real-time replication.

[![CI](https://github.com/ddouweb/rocketmq-mirror/actions/workflows/ci.yml/badge.svg)](https://github.com/ddouweb/rocketmq-mirror/actions/workflows/ci.yml)
[![Release](https://github.com/ddouweb/rocketmq-mirror/actions/workflows/release.yml/badge.svg)](https://github.com/ddouweb/rocketmq-mirror/actions/workflows/release.yml)
[![GitHub Release](https://img.shields.io/github/v/release/ddouweb/rocketmq-mirror)](https://github.com/ddouweb/rocketmq-mirror/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8%2B-orange.svg)](https://adoptium.net/)
[![Docker](https://img.shields.io/badge/docker-ghcr.io-blue)](https://github.com/ddouweb/rocketmq-mirror/pkgs/container/rocketmq-mirror)

`mqmirror` 把一个 RocketMQ 集群中的消息实时复制到另一个集群。它专为「**让本地开发/测试环境实时看到生产流量**」这个高频但长期没轻量解的场景设计,也适用于多机房同步、灾备、灰度影子集群等需求。

---

## 为什么需要它

RocketMQ 的 broker 把自身 IP 直接回报给客户端,跨 Docker host / 跨网络时客户端拿到这个 IP 通常连不通。Apache 官方提供了 [rocketmq-replicator](https://github.com/apache/rocketmq-connect/tree/master/rocketmq-connect-runtime),但它基于 Connect 框架,部署成本高,对中小团队是杀鸡用牛刀。

`mqmirror` 是这个生态位的极简替代方案:

| 维度 | rocketmq-replicator | **mqmirror** |
|------|--------------------|--------------|
| 部署形态 | Connect runtime + worker + 配置 | 单 JAR / 单容器 |
| 启动时间 | 分钟级 | 秒级 |
| 跨 Docker host 网络 | 需自行解决 | **内置 iptables DNAT** |
| 动态 topic 同步 | 需手动注册 | **自动发现** |
| 防环 | 较弱 | 内置 cluster id |
| 监控 | 需 Connect 体系 | **内置 Prometheus** |
| 学习成本 | 高 | 低 |

适合你:中小团队、想快速做生产数据回流、不想搭 Connect 集群、用 Docker Swarm 或 K8s。
不适合你:严格不丢消息(要事务/ Exactly-Once)、超大 TPS(单实例瓶颈)、需要复杂路由(用 Connect)。

---

## 应用场景

```
                          ┌───────────────────┐
                          │  生产 RocketMQ     │
                          │  (集群 A)         │
                          └─────────┬─────────┘
                                    │ 实时镜像
                       ┌────────────▼────────────┐
                       │       mqmirror          │
                       │  (容器/K8s/Swarm)       │
                       └────────────┬────────────┘
                                    │
            ┌───────────────────────┼───────────────────────┐
            ▼                       ▼                       ▼
   ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
   │  开发环境        │    │  压测/影子集群   │    │  灾备集群        │
   │  RocketMQ       │    │  RocketMQ       │    │  RocketMQ       │
   └─────────────────┘    └─────────────────┘    └─────────────────┘
```

- **生产数据回流**:本地开发联调时,实时看到生产的真实消息(topic 全量同步,新增 topic 自动跟上)
- **多机房同步**:异地容灾,跨网络用 iptables DNAT 解决 broker IP 问题
- **数据迁移**:配合白名单做灰度迁移,切换前后双写双消费
- **离线分析**:把消息复制到独立集群做大数据消费,不影响生产消费组

---

## 特性

- ✅ **零依赖**:JDK 8 + RocketMQ client 4.9.x 即可,无 Spring/Connect
- ✅ **轻量镜像**:multi-arch(amd64 + arm64)Docker 镜像 ~120MB,基于 Azul Zulu JRE 8 alpine
- ✅ **动态 topic 同步**:周期拉 nameserver,自动 subscribe/unsubscribe
- ✅ **白名单 / 全量**:配置 `TOPIC_WHITELIST` 只镜像指定 topic,不配则镜像全部业务 topic
- ✅ **跨网络场景支持**:容器化部署时,iptables DNAT 解决 broker 容器内 IP 跨 host 不可达
- ✅ **防环**:基于 cluster id 的 property 标记,双集群互镜不会形成消息风暴
- ✅ **消费语义可选**:`CONSUME_FROM=last|first|timestamp|stored` + `ON_FAILURE=reconsume|skip|halt`
- ✅ **Prometheus 指标**:开箱即用的 `/metrics` 端点,无第三方依赖
- ✅ **多场景部署**:Swarm / K8s / docker-compose 三套示例

---

## 先判断:你适合哪种部署方式

mqmirror 的部署难度,90% 取决于**远端 broker 上报的 IP 在你机器上能否直连**。先按决策树判断,避免走死胡同:

```
能 ping 通远端 broker 上报的 IP 吗?
├── 能 ────────────> 直接 java -jar(最简)
│                    详见 docs/STANDALONE.md
└── 不能(典型:容器内 IP)
    ├── 能改远端 broker.conf 加 brokerIP1=可达IP 吗?
    │   ├── 能 ────> 改完变上一行,java -jar(治本)
    │   └── 不能 ──> 必须用容器,NAT 自动化
    │                ├── Docker Swarm(本文档 / swarm.yaml)
    │                ├── Kubernetes(deploy/k8s.yaml)
    │                └── docker-compose(本地开发)
    └── 都不行 ────> 宿主机 + 手动 NAT(不推荐,见 STANDALONE.md)
```

完整决策树和每条路的细节见 [deploy/README.md](deploy/README.md)。

---

## 快速开始(容器化场景)

> 如果你已经判断自己走「broker IP 可达」这条路(场景 A),直接看 [docs/STANDALONE.md](docs/STANDALONE.md) 用 `java -jar` 即可,不需要这一节。
>
> 这一节面向**必须用容器解决网络问题**的场景。

### 1. 构建镜像

swarm.yaml / docker-compose.yaml 默认用官方多架构镜像(`amd64` + `arm64`,~120MB)。

**选项 A — 直接拉官方镜像**(推荐):

```bash
docker pull ghcr.io/ddouweb/rocketmq-mirror:latest
```

**选项 B — 本地构建**(多阶段构建,自动编译,不需要先 mvn package):

```bash
docker build -t ghcr.io/ddouweb/rocketmq-mirror:latest .
```

> 本地 build 出的镜像用 `ghcr.io/...` 这个名字,这样 swarm.yaml / docker-compose.yaml 不用改 image 字段就能直接用。

### 2. 起一套本地 RocketMQ + mqmirror

```bash
# 编辑 swarm.yaml 把 REMOTE_NAMESRV 改成你的生产 nameserver 地址
docker swarm init
docker stack deploy -c swarm.yaml rocketmq
```

### 3. 验证

打开 [http://localhost:9100/metrics](http://localhost:9100/metrics) 看 Prometheus 指标:

```
mqmirror_consumed_total 12345
mqmirror_mirrored_total{result="success"} 12340
mqmirror_mirrored_total{result="failed"} 5
mqmirror_active_topics{kind="subscribed"} 47
```

想用 RocketMQ 控制台 Web UI?(默认不启动,按需拉起):

```bash
docker service scale rocket_mqconsole=1      # Swarm
# 或 docker compose --profile console up -d mqconsole  # docker-compose
```

然后打开 [http://localhost:8080](http://localhost:8080),应该能看到本地集群已经有了远端的 topic。

```
mqmirror_consumed_total 12345
mqmirror_mirrored_total{result="success"} 12340
mqmirror_mirrored_total{result="failed"} 5
mqmirror_active_topics{kind="subscribed"} 47
```

---

## 配置项

完整列表见 [docs/CONFIG.md](docs/CONFIG.md)。最常用的:

| 环境变量 | 默认 | 说明 |
|---------|------|------|
| `REMOTE_NAMESRV` | **必填** | 远端(源)nameserver 地址 |
| `LOCAL_NAMESRV` | `namesrv:9876` | 本地 nameserver 地址 |
| `TOPIC_WHITELIST` | 空(全量) | 逗号分隔的 topic 列表 |
| `REFRESH_SEC` | `30` | topic 列表刷新间隔(秒) |
| `CLUSTER_ID` | `default` | 本集群 id,**双向同步时两端必须不同** |
| `LOOP_PREVENTION` | `true` | 是否开启防环 |
| `CONSUME_FROM` | `last` | 启动时从哪里开始消费 |
| `ON_FAILURE` | `reconsume` | send 失败时如何处理 |
| `METRICS_PORT` | `9100` | Prometheus 端口,设为 0 关闭 |
| `REMOTE_BROKER_CONTAINER_IP` | 空 | iptables DNAT 用,见部署文档 |
| `REMOTE_BROKER_HOST_IP` | 空 | iptables DNAT 用 |

---

## 部署

不同部署方式难度差异很大,**先看 [deploy/README.md](deploy/README.md) 的决策树判断你的场景**。简述:

| 场景 | 难度 | 文档 |
|------|------|------|
| broker IP 可达 → `java -jar` | ⭐ | [docs/STANDALONE.md](docs/STANDALONE.md) |
| Docker Swarm 跨主机(iptables 自动) | ⭐⭐ | [swarm.yaml](swarm.yaml) |
| Kubernetes(直连) | ⭐⭐ | [deploy/k8s.yaml](deploy/k8s.yaml) |
| 本地 docker-compose(演示) | ⭐ | [docker-compose.yaml](docker-compose.yaml) |
| 双向同步 | ⭐⭐ | [deploy/swarm-bidirectional.yaml](deploy/swarm-bidirectional.yaml) |
| 宿主机 + 手动 NAT(不推荐) | ⭐⭐⭐⭐ | [docs/STANDALONE.md](docs/STANDALONE.md) 最后一段 |

---

## 工作原理

```
┌──────────────────────────────────────────────────────────────────┐
│                       mqmirror 进程                              │
│                                                                  │
│  ┌─────────────────┐    ┌──────────────────┐                    │
│  │ TopicSync       │───▶│ Consumer         │                    │
│  │ (周期拉 topics) │    │ (cluster mode)   │                    │
│  └─────────────────┘    └────────┬─────────┘                    │
│                                  │ MessageExt                    │
│                                  ▼                               │
│                         ┌──────────────────┐                    │
│                         │ MirrorListener   │                    │
│                         │  - 防环检测       │                    │
│                         │  - property 过滤  │                    │
│                         │  - send 到本地    │                    │
│                         └────────┬─────────┘                    │
│                                  │                               │
│  ┌─────────────────┐    ┌────────▼─────────┐                    │
│  │ MetricsExporter │    │ Producer         │                    │
│  │ /metrics HTTP   │    │ (local cluster)  │                    │
│  └─────────────────┘    └──────────────────┘                    │
└──────────────────────────────────────────────────────────────────┘
```

防环机制:转发时打 property `__mqmirror.cluster=<CLUSTER_ID>`,消费时若该值等于自身则跳过。

---

## 限制与已知行为

- **消费位点**:默认 `CONSUME_FROM=last`,即启动后只消费新消息。要回放历史改 `CONSUME_FROM=first` 或 `timestamp`。
- **失败重投可能重复**:`ON_FAILURE=reconsume` 时,batch 中任一消息 send 失败会导致整个 batch 重投,成功的部分会被重复消费。**业务消费方需要幂等**。
- **顺序消息**:并发消费模式,不保证顺序。如果业务严格依赖顺序,需要自己改造或考虑别的方案。
- **事务消息**:不感知事务半消息,会镜像已经 commit 的最终消息。
- **TPS 上限**:单实例受 producer 同步 send 限制,大致万级 TPS。更高 TPS 需要多实例 + 业务侧分片。
- **DLQ/RETRY topic 不镜像**:系统 topic 显式过滤。

---

## Roadmap

- [ ] 异步 send(`sendOneway` 或批量)提高 TPS
- [ ] 顺序消息透传
- [ ] 消息体压缩
- [ ] 跨集群位点对齐(便于切换消费组)
- [x] Grafana dashboard 模板(见 [grafana/](grafana/README.md))

欢迎提 issue / PR。

---

## License

MIT License。详见 [LICENSE](LICENSE)。

> 注:根目录 `broker.conf` 是 Apache RocketMQ(Apache 2.0)项目附带的配置文件示例,版权归 Apache Software Foundation,在本仓库中以配置参考目的保留。
