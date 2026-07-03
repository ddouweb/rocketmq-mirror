# 贡献指南

感谢你考虑为 mqmirror 贡献代码!这篇指南说明如何参与。

## 行为准则

参与本项目的每一位贡献者都需要遵守 [Code of Conduct](CODE_OF_CONDUCT.md)。请保持友善、尊重。

## 开发环境

- JDK 8+(CI 矩阵跑 8/11/17/21)
- Maven 3.6+
- Docker(可选,构建镜像用)

## 本地开发流程

```bash
git clone https://github.com/<your-fork>/mqmirror.git
cd mqmirror/mqmirror

mvn test            # 跑单元测试
mvn package         # 打包(包含 shade 出的 fat jar)
mvn -B -ntp verify  # 同 CI 流程

# 构建本地 Docker 镜像
docker build -t mqmirror:dev .
```

## 提 PR 前的检查清单

- [ ] `mvn test` 全过(包含新增的测试)
- [ ] 新增功能写了对应的单元测试
- [ ] 公共配置项变更已更新 `docs/CONFIG.md`
- [ ] 行为变化已记录到 `CHANGELOG.md` 的 `[Unreleased]` 段
- [ ] commit message 遵循 [Conventional Commits](https://www.conventionalcommits.org/):
  - `feat: 支持异步 send`
  - `fix: 防环 property 被业务覆盖的边界 case`
  - `docs: 补充 K8s 部署示例`
  - `refactor: 拆分 MirrorApp`

## 提 Issue

**Bug 报告**请附:
- mqmirror 版本(`docker inspect` 或 jar 名)
- RocketMQ 版本(源端 + 目标端)
- Java 版本
- 完整的环境变量配置(隐去敏感信息)
- 最小复现步骤
- 相关日志(从 stdout 或 `/metrics` 看到的指标)

**Feature request**请说明:
- 你的使用场景
- 当前为什么做不到
- 你期望的接口/行为

## 分支与发布

- `main` 主干,所有 PR 目标分支
- `feature/*` 功能开发分支
- `fix/*` bug 修复分支

### 发布流程(维护者)

1. 在 `main` 上确认 `CHANGELOG.md` 的 `[Unreleased]` 段已整理好
2. 把 `[Unreleased]` 改成 `[1.x.x] - YYYY-MM-DD`,新开一个空的 `[Unreleased]`
3. commit:`chore: release v1.x.x`
4. 打 tag:`git tag v1.x.x && git push origin v1.x.x`
5. GitHub Actions `release.yml` 自动:
   - 构建 amd64 + arm64 多架构镜像
   - 推送到 `ghcr.io/<owner>/<repo>:latest` 和 `:v1.x.x`
   - 创建 GitHub Release(自动生成 release notes,附带 mqmirror.jar)
6. 在 Release 页面补充重要变更说明

## 代码风格

- 4 空格缩进
- 类、方法用 Javadoc 说明意图( WHY 而不是 WHAT )
- 单一职责,避免类超过 300 行
- 公共 API 拒绝 null,用 `Optional` 或显式抛异常

## 许可

提交的代码默认以 [MIT](LICENSE) 许可发布。
