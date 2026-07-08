# Grafana Dashboard

`mqmirror-dashboard.json` 是 mqmirror 的开箱即用监控面板,覆盖消息速率、累计计数、send 延迟、吞吐量、topic 数、错误率。

---

## 前提

需要 Prometheus 已经在抓取 mqmirror 的 `/metrics` 端点。最小 scrape 配置:

```yaml
scrape_configs:
  - job_name: 'mqmirror'
    static_configs:
      - targets: ['<mqmirror-host>:9100']   # METRICS_PORT,默认 9100
```

K8s 用户用 `deploy/k8s.yaml` 里自带的 ServiceMonitor(Prometheus Operator 自动发现)。

---

## 导入步骤

1. 打开 Grafana → 左侧菜单 **Dashboards** → **New** → **Import**
2. 点 **Upload dashboard file**,选 `mqmirror-dashboard.json`
3. 在最后一步选你的 **Prometheus** 数据源
4. **Import**

import 后面板顶部会自动出现时间范围和刷新控件(默认 last 1h,10s 刷新)。

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

如果跑了多个 mqmirror(双向同步 / 多机房),面板里的查询都用了 `sum(...)` 聚合,默认显示所有实例的总和。想按实例拆分,把查询里的 `sum(...)` 去掉,让 Grafana 按 `instance` 标签自动生成多条线。
