# Grafana Dashboard

`mqmirror-dashboard.json` 是 mqmirror 的开箱即用监控面板,10 个面板覆盖消息速率、累计计数、send 延迟、吞吐量、topic 数、错误率。

两种用法,按你现有环境选:

- **方式 1:一键起监控栈** — 本地没 Prometheus/Grafana,想快速验证。用 `docker-compose.metrics.yaml` 一条命令起整套(自动加载数据源 + dashboard)
- **方式 2:接入现有 Prometheus/Grafana** — 已经有监控基础设施,只导入面板

---

## 方式 1:一键起监控栈(推荐本地验证)

前提:mqmirror 已经在跑,METRICS_PORT 暴露在宿主机。

```bash
# 在仓库根目录执行
docker compose -f grafana/docker-compose.metrics.yaml up -d
```

这条命令会起两个容器:

| 服务 | 地址 | 说明 |
|------|------|------|
| Prometheus | http://localhost:9090 | 抓取 mqmirror 的 `/metrics`(默认抓 9100 和 9101) |
| Grafana | http://localhost:3000 | admin / admin,首次登录会让你改密码(可跳过) |

Grafana 启动时通过 provisioning **自动**:
- 注册 Prometheus 数据源(uid = `Prometheus`)
- 加载 `mqmirror-dashboard.json`

打开 http://localhost:3000 → Dashboards,直接看到 **mqmirror** 面板,无需手动 import。

```bash
# 停止
docker compose -f grafana/docker-compose.metrics.yaml down
```

> 抓取目标在 `grafana/prometheus.yml` 里。如果改了 `METRICS_PORT` 或 mqmirror 跑在别的机器,编辑 targets。

---

## 方式 2:接入现有 Prometheus/Grafana

**1. 让 Prometheus 抓取 mqmirror**

```yaml
scrape_configs:
  - job_name: 'mqmirror'
    static_configs:
      - targets: ['<mqmirror-host>:9100']   # METRICS_PORT,默认 9100
```

K8s 用户用 `deploy/k8s.yaml` 里自带的 ServiceMonitor(Prometheus Operator 自动发现)。

**2. 导入 dashboard**

1. Grafana → **Dashboards** → **New** → **Import**
2. **Upload dashboard file**,选 `mqmirror-dashboard.json`
3. 选你的 Prometheus 数据源(**注意:面板里 datasource uid 硬编码为 `Prometheus`**,如果你的数据源 uid 不是这个,import 后进 panel 设置改一下,或把数据源 uid 改成 `Prometheus`)
4. **Import**

---

## 面板说明

| 面板 | 指标 | 含义 |
|------|------|------|
| 镜像 TPS | `rate(mqmirror_mirrored_total{result="success"}[1m])` | 每秒成功镜像的消息数 |
| 失败率 | failed / consumed | 失败消息占比,>5% 变红 |
| 累计镜像成功 | `mqmirror_mirrored_total{result="success"}` | 启动至今累计 |
| 订阅 Topic 数 | `mqmirror_active_topics{kind="subscribed"}` | 当前订阅的 topic 数 |
| 消息速率 | consumed / success / failed | 三条速率线对比 |
| 累计计数 | consumed / success / loopback-skipped | 累计趋势 |
| Send 延迟 | last / avg | producer 发送延迟 |
| 吞吐量 | `rate(mqmirror_bytes_total[1m])` | 字节/秒 |
| Topic 数 | subscribed / discovered | 订阅 vs 发现 |
| 错误速率 | send-errors / subscribe-errors | 出错速率 |

---

## 多实例

跑了多个 mqmirror(双向同步 / 多机房)时,面板里的查询都用了 `sum(...)` 聚合,默认显示所有实例总和。想按实例拆分,把查询里的 `sum(...)` 去掉,Grafana 会按 `instance` 标签自动生成多条线。
