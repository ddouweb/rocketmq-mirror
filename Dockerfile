# ---- Stage 1: build fat jar ----
FROM maven:3.9-eclipse-temurin-8 AS builder
WORKDIR /build

# 缓存层:只拷 pom.xml 预拉依赖,pom 不变时复用
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package && mv target/mqmirror-*-shaded.jar /build/mqmirror.jar

# ---- Stage 2: minimal runtime ----
FROM eclipse-temurin:8-jre-alpine

# iptables 用于 entrypoint.sh 里的 DNAT(跨 Docker host 场景)
# tini 作为 init,正确处理 SIGTERM 信号(否则 Java 进程可能不优雅退出)
# bash 用于 entrypoint.sh 的数组语法
RUN apk add --no-cache iptables tini bash \
 && rm -rf /var/cache/apk/*

WORKDIR /app
COPY --from=builder /build/mqmirror.jar /app/mqmirror.jar
COPY entrypoint.sh /app/entrypoint.sh
RUN sed -i 's/\r$//' /app/entrypoint.sh \
 && chmod +x /app/entrypoint.sh

# 让 Java 8 识别容器内存限制
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["/sbin/tini", "--", "/app/entrypoint.sh"]
