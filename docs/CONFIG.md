# 配置参考

所有配置通过环境变量传入。本文档逐项说明。

---

## 必填

| 变量 | 示例 | 说明 |
|------|------|------|
| `REMOTE_NAMESRV` | `1.2.3.4:9876` | 远端(源)集群的 nameserver 地址,多个用分号分隔 |

---

## 网络

| 变量 | 默认 | 说明 |
|------|------|------|
| `LOCAL_NAMESRV` | `namesrv:9876` | 本地(目标)集群的 nameserver 地址 |
| `REMOTE_BROKER_CONTAINER_IP` | 空 | iptables 用:远端 broker 在容器内上报的 IP,逗号分隔多个 |
| `REMOTE_BROKER_HOST_IP` | 空 | iptables 用:对应的物理机可达 IP,数量必须与上面对应 |

`REMOTE_BROKER_*_IP` 同时配置时启用 DNAT,详见 [部署文档](../deploy/README.md)。

---

## 消费组

| 变量 | 默认 | 说明 |
|------|------|------|
| `CONSUMER_GROUP` | `mirror-consumer` | 远端消费组名,建议在远端控制台单独建组便于监控 |
| `PRODUCER_GROUP` | `mirror-producer` | 本地生产组名 |

> 双向同步时,两端的 group 名应该带方向标识,例如 `mirror-to-dev` / `mirror-to-prod`,避免误用。

---

## Topic 同步策略

| 变量 | 默认 | 说明 |
|------|------|------|
| `TOPIC_WHITELIST` | 空(全量) | 逗号分隔 topic 列表,设了只镜像这些;留空则镜像所有非系统 topic |
| `REFRESH_SEC` | `30` | topic 列表刷新周期(秒)。全量模式下,新增 topic 在这个周期内会被自动跟上 |

白名单示例:
```
TOPIC_WHITELIST=order-paid,order-cancelled,user-registered
TOPIC_WHITELIST=topic-a,, topic-b,    # 容错:多余逗号/空格会被忽略
```

---

## 消费语义

### `CONSUME_FROM`

控制启动后从何处开始消费。可选值:

| 值 | 行为 |
|----|------|
| `last`(默认) | 只消费启动后产生的新消息 |
| `first` | 从 topic 最早的消息开始(回放历史,慎用,数据量大时会拉很久) |
| `timestamp` | 从 `CONSUME_TIMESTAMP` 指定的时间点开始 |
| `stored` | 用 broker 上记录的消费位点;首次启动等同 `last` |

注意:首次启动后,RocketMQ 会把消费位点持久化到 broker;**后续重启无论怎么设 `CONSUME_FROM`,只要位点已存在,默认会从位点继续**。要强制重新消费,需要在控制台重置消费位点。

### `CONSUME_TIMESTAMP`

仅当 `CONSUME_FROM=timestamp` 时生效。毫秒时间戳:
```
CONSUME_TIMESTAMP=1719500000000
```

### `ON_FAILURE`

控制单条消息 send 到本地集群失败时的处理:

| 值 | 行为 | 适用 |
|----|------|------|
| `reconsume`(默认) | 稍后重投整个 batch | 通用,但**业务消费方必须幂等** |
| `skip` | 失败的消息直接丢弃,只在 `mqmirror_send_errors_total` 体现 | 数据可丢、不能阻塞的场景 |
| `halt` | 任一失败立刻 `System.exit(2)`,让编排层重启 | 严格不丢数据,接受服务中断 |

`halt` 适合「宁可全停也不要丢一条」的金融/计费场景;`skip` 适合日志/监控类容忍丢失的场景。

---

## 发送语义

### `SEND_MODE`

控制转发到本地集群时 producer 的发送方式:

| 值 | 行为 | TPS | 失败处理 |
|----|------|-----|---------|
| `sync`(默认) | `producer.send()` 同步等每条 ACK | 基线 | 失败按 `ON_FAILURE` 处理(可重投) |
| `async` | `producer.send(msg, callback)`,提交后立即乐观 ACK | 高(约 3–5×) | 回调失败只记 metrics,**无法重投**(消息已 ACK) |
| `oneway` | `producer.sendOneway()`,fire-and-forget | 最高 | 不检测失败(连提交成功都不保证) |

三档都遵守:**提交阶段**(`send` / `sendOneway` 本身抛异常,如 producer 未启动、网络断)的失败仍走 `ON_FAILURE`。

**async / oneway 的关键取舍**:消息一旦提交,无论回调 / oneway 是否真正送达,batch 都已对 broker 返回 `CONSUME_SUCCESS`,位点前进。因此:
- `ON_FAILURE=reconsume` 在 async / oneway 下**只能保护提交异常**,无法保护回调失败
- 启动时若 `SEND_MODE != sync` 且 `ON_FAILURE != skip`,会打一条 WARN 日志提醒

**建议**:
- 默认 `sync`,需要可重投保证时不要改
- 追求吞吐、业务能容忍偶发丢失(日志 / 监控类)→ `async`
- 极限吞吐、完全不在意丢失 → `oneway`

---

## 防环

| 变量 | 默认 | 说明 |
|------|------|------|
| `CLUSTER_ID` | `default` | 本集群标识,会打在每条转发消息的 property 上 |
| `LOOP_PREVENTION` | `true` | 是否在消费时检测并跳过自身发出的消息 |

工作原理:转发时打 property `__mqmirror.cluster=<CLUSTER_ID>`,消费时如果该值等于自身则跳过。

**双向同步必须**:
- 两端 `CLUSTER_ID` 不同
- 两端 `LOOP_PREVENTION=true`

否则会形成"A 镜像到 B,B 把它当新消息再镜像回 A"的循环放大。

单向同步时 `CLUSTER_ID` 任意,但建议都设上有意义的名,便于排查。

---

## 监控

| 变量 | 默认 | 说明 |
|------|------|------|
| `METRICS_PORT` | `9100` | Prometheus HTTP 端口;设为 `0` 关闭 |

抓取配置示例:
```yaml
scrape_configs:
  - job_name: 'mqmirror'
    static_configs:
      - targets: ['mqmirror-host:9100']
```

指标列表见 [部署文档](../deploy/README.md#监控接入)。

---

## 完整最小配置示例

```bash
# 必填
REMOTE_NAMESRV=prod-namesrv.internal:9876

# 推荐
LOCAL_NAMESRV=rocketmq-namesrv:9876
CLUSTER_ID=dev-local
TOPIC_WHITELIST=topic-a,topic-b
ON_FAILURE=reconsume
```

## 完整生产配置示例

```bash
REMOTE_NAMESRV=prod-namesrv-1:9876;prod-namesrv-2:9876
LOCAL_NAMESRV=namesrv:9876
CONSUMER_GROUP=mirror-to-dev
PRODUCER_GROUP=mirror-to-dev
REFRESH_SEC=30
CLUSTER_ID=dev-beijing
LOOP_PREVENTION=true
CONSUME_FROM=last
ON_FAILURE=reconsume
METRICS_PORT=9100
REMOTE_BROKER_CONTAINER_IP=10.0.0.1,10.0.0.2
REMOTE_BROKER_HOST_IP=192.168.8.130,192.168.8.131
```

---

## 调优建议

- **TPS 偏低**:把 `SEND_MODE` 从 `sync` 改成 `async`(回调仍统计成功/失败,约 3–5× TPS)或 `oneway`(最快但无法检测失败)。代价是 async/oneway 的回调失败不重投,见[发送语义](#发送语义)。
- **延迟高**:看 `mqmirror_send_duration_seconds_avg`。>100ms 通常意味着本地 broker 慢或网络抖。
- **大消息**:RocketMQ producer 默认对 >4KB 的 body 自动 zip 压缩(`compressLevel=5`),broker 存压缩态、消费端透明解压,mqmirror 无需额外配置。
- **积压**:看 `mqmirror_consumed_total` 增速 vs `mqmirror_mirrored_total` 增速,前者高于后者说明 producer 跟不上,考虑加 mqmirror 实例(注意消费组分摊)。
- **消费滞后**:用 RocketMQ 控制台看消费位点距离最大位点差距,而不是看 mqmirror 自身指标。
