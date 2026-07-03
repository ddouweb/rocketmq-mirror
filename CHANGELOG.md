# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Planned
- 异步 send(`sendOneway` / 批量)提高 TPS
- 顺序消息透传
- Grafana dashboard 模板

## [1.0.0] - 2026-07-03

### Added
- **核心功能**:RocketMQ 跨集群消息实时镜像
- **动态 topic 同步**:周期拉 nameserver 自动 subscribe/unsubscribe
- **白名单 / 全量两种模式**:`TOPIC_WHITELIST` 可选
- **防环检测**:基于 `__mqmirror.cluster` property,支持双集群互镜
- **消费语义可选**:`CONSUME_FROM=last|first|timestamp|stored`
- **失败处理策略**:`ON_FAILURE=reconsume|skip|halt`
- **内置 Prometheus /metrics endpoint**(零第三方依赖)
- **iptables DNAT** 解决 RocketMQ broker 容器内 IP 跨 host 不可达
- **多 broker 支持**:DNAT 配置支持逗号分隔多对 IP
- **三种部署示例**:Swarm / K8s / docker-compose
- **双向同步部署示例**
- **29 个单元测试**覆盖系统 topic 判断、白名单解析、diff 计算、防环、指标

### Documentation
- README 含场景图、与 rocketmq-replicator 对比表
- docs/CONFIG.md 逐项配置参考
- deploy/README.md 三种部署方式详解

### Infrastructure
- MIT License
- GitHub Actions CI(JDK 8/11/17/21 矩阵跑测试)
- GitHub Actions Release(打 tag 自动构建 amd64+arm64 镜像推到 ghcr.io)
- Docker dev build 工作流
