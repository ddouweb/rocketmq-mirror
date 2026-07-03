---
name: Bug Report
about: 报告一个 bug,帮助改进
title: "[BUG] "
labels: bug
assignees: ''
---

## 现象

<!-- 简短描述发生了什么 -->

## 期望行为

<!-- 你认为应该是什么行为 -->

## 复现步骤

1.
2.
3.

## 环境

- mqmirror 版本:
- RocketMQ 版本(源端):
- RocketMQ 版本(目标端):
- Java 版本:
- 部署方式(在 [ ] 里填 x):

  - [ ] Docker Swarm + iptables DNAT
  - [ ] Kubernetes
  - [ ] docker-compose
  - [ ] 直接 java -jar

## 配置

<!-- 隐去敏感信息(IP、密码等) -->

```
REMOTE_NAMESRV=...
LOCAL_NAMESRV=...
CLUSTER_ID=...
CONSUME_FROM=...
ON_FAILURE=...
(其他相关配置)
```

## 日志

```
# 把相关日志贴这里
# 也可以贴 /metrics 的输出:
# curl http://<mqmirror-host>:9100/metrics
```

## 其他

<!-- 你尝试过什么?搜索过 issue 吗? -->
