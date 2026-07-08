# 宿主机 java -jar 直接运行

> 这份文档讲清楚:什么时候可以直接 `java -jar` 跑 mqmirror、什么时候不能、不能的时候有哪些选择。

---

## TL;DR

```
你能否 ping 通远端 broker 上报的 IP?
├── 能 ──> java -jar 一行跑起来,这是本文档推荐方案
└── 不能 ──> 不要走 java -jar 这条路,改用容器 deploy/swarm.yaml
```

---

## 为什么这是问题

RocketMQ 客户端访问 broker 的流程:

1. 客户端连 nameserver(TCP, 9876 端口)
2. nameserver 返回 topic 路由数据,**含 broker 上报的 IP**
3. 客户端**直接 TCP 连这个 IP:10911**

**关键点**:第 3 步用的是 broker 自己上报的 IP。如果 broker 上报的是容器内 IP(常见于 Docker 部署),你在另一台机器上跑 java -jar,这个 IP 不可达,就废了。

**纯 Java 层面没法拦截这个流程**,因为客户端拿到 IP 后是直接 OS socket 连接。所以 broker IP 不可达时,唯一的物理解是 OS 层 NAT。

---

## 推荐用法:broker IP 可达的场景

### 适用人群

- 你拥有远端 broker 的配置权,可以改 `broker.conf`
- 远端 broker 已经上报了可达 IP(公网 / 局域网 IP / hostNetwork)
- 远端 broker 就跑在你这台机器上(本地开发)

### 怎么判断 broker IP 可达

```bash
# 方法 1:连远端 nameserver 看 brokerAddrTable(用 DefaultMQAdminExt 或 rocketmq-dashboard)
# 在 rocketmq-dashboard 的 Cluster 页面能看到每个 broker 的地址

# 方法 2:直接 TCP 探测
# Linux / macOS / Windows(WSL)
nc -zv <broker-ip> 10911
# 或
telnet <broker-ip> 10911

# Windows PowerShell
Test-NetConnection -ComputerName <broker-ip> -Port 10911
```

能连通 → broker IP 可达,继续往下。连不通 → 看本文档最后一节。

### 启动命令

**Linux / macOS / WSL**:
```bash
export REMOTE_NAMESRV=prod-namesrv:9876
export LOCAL_NAMESRV=127.0.0.1:9876
export CLUSTER_ID=local-dev
# 可选
# export TOPIC_WHITELIST=topic-a,topic-b
# export CONSUME_FROM=last
# export ON_FAILURE=reconsume
# export METRICS_PORT=9100
java -jar mqmirror.jar
```

**Windows CMD**:
```cmd
set REMOTE_NAMESRV=prod-namesrv:9876
set LOCAL_NAMESRV=127.0.0.1:9876
set CLUSTER_ID=local-dev
java -jar mqmirror.jar
```

**Windows PowerShell**:
```powershell
$env:REMOTE_NAMESRV="prod-namesrv:9876"
$env:LOCAL_NAMESRV="127.0.0.1:9876"
$env:CLUSTER_ID="local-dev"
java -jar mqmirror.jar
```

完整配置项见 [CONFIG.md](CONFIG.md)。

### 让 broker 上报可达 IP(治本)

如果你能改远端 broker 配置,这是最干净的方案。在远端 broker 的 `broker.conf` 加:

```
brokerIP1 = <本机可达的 IP>
```

例如 broker 跑在 `192.168.8.130` 这台机器上,但 Docker 容器内 IP 是 `192.168.1.99`。默认 broker 上报容器内 IP,改完后上报物理 IP,所有客户端都能直连。

改完重启 broker,然后 java -jar 不需要任何 NAT。

### 后台运行

**Linux / macOS**:用 systemd 或 nohup
```bash
# nohup(简单)
nohup java -jar mqmirror.jar > mqmirror.log 2>&1 &

# systemd(推荐生产用)
# /etc/systemd/system/mqmirror.service
[Unit]
Description=RocketMQ Mirror
After=network.target

[Service]
Environment=REMOTE_NAMESRV=prod-ns:9876
Environment=LOCAL_NAMESRV=127.0.0.1:9876
Environment=CLUSTER_ID=prod-mirror
ExecStart=/usr/bin/java -jar /opt/mqmirror/mqmirror.jar
Restart=on-failure
User=mqmirror

[Install]
WantedBy=multi-user.target
```

**Windows**:用 NSSM 或任务计划程序
```cmd
:: 用 NSSM 装成服务(路径换成你机器上 Java 8+ 的 java.exe)
nssm install mqmirror "C:\Program Files\Java\jdk1.8.0_301\bin\java.exe" "-jar C:\mqmirror\mqmirror.jar"
nssm set mqmirror AppEnvironmentExtra REMOTE_NAMESRV=prod-ns:9876 LOCAL_NAMESRV=127.0.0.1:9876 CLUSTER_ID=prod-mirror
nssm start mqmirror
```

---

## 不推荐方案:broker IP 不可达 + 宿主机

> ⚠️ 这一段是 reference,不是推荐。**认真考虑改 broker 配置或用容器**之后再回来这里。

如果你确定要走这条路(既改不了远端、又不能用 Docker),按 OS 选一种 NAT 方案。

### Linux + iptables(等价于容器内的方案)

```bash
# 一次性配置(重启后失效,要持久化写到 /etc/network/if-up.d/ 或 firewalld)
sudo iptables -t nat -A OUTPUT -d 192.168.1.99 -p tcp --dport 10911 -j DNAT --to-destination 192.168.8.130:10911
sudo iptables -t nat -A OUTPUT -d 192.168.1.99 -p tcp --dport 10912 -j DNAT --to-destination 192.168.8.130:10912

# 然后正常跑
REMOTE_NAMESRV=192.168.8.130:9876 LOCAL_NAMESRV=127.0.0.1:9876 java -jar mqmirror.jar

# 清理
sudo iptables -t nat -D OUTPUT -d 192.168.1.99 -p tcp --dport 10911 -j DNAT --to-destination 192.168.8.130:10911
sudo iptables -t nat -D OUTPUT -d 192.168.1.99 -p tcp --dport 10912 -j DNAT --to-destination 192.168.8.130:10912
```

iptables 在 OUTPUT 链改目的地址,**不需要这个 IP 真实存在**于本机网卡。这是 Linux 的优势。

### Windows + netsh portproxy(复杂)

Windows 的 netsh portproxy 只能 listen 在本地真实存在的 IP 上,所以**必须先加 IP 别名**。

```cmd
:: 1. 以管理员身份打开 cmd,加 IP 别名到 loopback
::    先 ipconfig 看 "Loopback Pseudo-Interface 1" 是否存在,不存在就装一个
netsh interface ipv4 add address "Loopback Pseudo-Interface 1" 192.168.1.99 255.255.255.255

:: 2. 加端口转发
netsh interface portproxy add v4tov4 listenaddress=192.168.1.99 listenport=10911 connectaddress=192.168.8.130 connectport=10911
netsh interface portproxy add v4tov4 listenaddress=192.168.1.99 listenport=10912 connectaddress=192.168.8.130 connectport=10912

:: 3. 验证
netsh interface portproxy show all

:: 4. 跑 mqmirror
set REMOTE_NAMESRV=192.168.8.130:9876
set LOCAL_NAMESRV=127.0.0.1:9876
java -jar mqmirror.jar
```

清理:
```cmd
netsh interface portproxy delete v4tov4 listenaddress=192.168.1.99 listenport=10911
netsh interface portproxy delete v4tov4 listenaddress=192.168.1.99 listenport=10912
netsh interface ipv4 delete address "Loopback Pseudo-Interface 1" 192.168.1.99
```

**坑点**:
- 防火墙可能拦 10911/10912,需要 `netsh advfirewall firewall add rule ...` 放行
- 重启后 portproxy 保留,address 不保留(要写脚本自启)
- 不支持多 broker 的优雅配置

### macOS + pfctl(更复杂)

macOS 没有 iptables 也没有 netsh,只能用 pfctl。需要在 `/etc/pf.conf` 加 rdr 规则,而且需要本地 IP 真实存在(用 `ifconfig lo0 alias`)。

更现实的选择是装 socat:
```bash
# 装 socat
brew install socat

# 给 loopback 加别名
sudo ifconfig lo0 alias 192.168.1.99

# 起 socat 转发(每个端口一个进程)
socat TCP4-LISTEN:10911,fork,bind=192.168.1.99 TCP4:192.168.8.130:10911 &
socat TCP4-LISTEN:10912,fork,bind=192.168.1.99 TCP4:192.168.8.130:10912 &

# 跑
REMOTE_NAMESRV=192.168.8.130:9876 LOCAL_NAMESRV=127.0.0.1:9876 java -jar mqmirror.jar

# 清理
sudo ifconfig lo0 -alias 192.168.1.99
```

---

## 为什么不把这些自动化进工具

可以,但代价大:

- Linux 上 mqmirror 自己执行 iptables 命令需要 root,普通用户跑不了
- Windows 上 mqmirror 自己执行 netsh 也需要管理员,且 IP 别名涉及网卡枚举,代码复杂
- macOS pfctl 改 `/etc/pf.conf` 风险更高

容器化方案之所以行,是因为容器天然有 NET_ADMIN cap + 隔离的网络命名空间。宿主机 java -jar 没这条件。

所以这个工具的设计取舍是:

- **能改远端 / broker IP 可达** → java -jar 一行(本文档上半段)
- **改不了远端** → 容器化(NAT 自动化,见 deploy/swarm.yaml)
- **既改不了远端、又不能用容器** → 不强求,文档给 reference 但不保证好用

---

## 启动失败诊断

跑起来报错时按这个顺序查:

### 1. `missing required env: REMOTE_NAMESRV`

环境变量没传进去。检查 `echo $REMOTE_NAMESRV`(Linux/Mac)或 `echo %REMOTE_NAMESRV%`(Win CMD)或 `$env:REMOTE_NAMESRV`(PowerShell)。

### 2. `org.apache.rocketmq.remoting.exception.RemotingConnectException: connect to <x.x.x.x:10911> failed`

**99% 是 broker IP 不可达**。回到顶部「怎么判断 broker IP 可达」一节,先 `telnet` / `Test-NetConnection` 测一下。

### 3. `connect to nameserver failed`

nameserver 本身不通,检查 `REMOTE_NAMESRV` 是否正确、网络是否通。

### 4. 镜像启动了但 `mqmirror_mirrored_total` 不增长

- 看本地 broker 是否真有数据(rocketmq-dashboard 看 topic)
- 看 `mqmirror_active_topics{kind="subscribed"}` 是不是 0(为 0 说明 topic 还没同步,等 `REFRESH_SEC` 秒)
- 看 `mqmirror_loopback_skipped_total` 是不是在涨(在涨说明 `CLUSTER_ID` 配置有问题,见防环机制)
