#!/bin/bash
set -e

# 可选:iptables DNAT,把 broker 容器内 IP 重定向到远程 docker host 的映射端口。
# 仅当 REMOTE_BROKER_CONTAINER_IP / REMOTE_BROKER_HOST_IP 同时配置时启用。
# 支持逗号分隔多对 IP,例如:
#   REMOTE_BROKER_CONTAINER_IP=10.0.0.1,10.0.0.2
#   REMOTE_BROKER_HOST_IP=192.168.8.130,192.168.8.131
# 用法 1: docker swarm 跨 host 时,远程 broker 在容器内 IP 不可达,需要 DNAT 到物理机 IP。
# 用法 2: K8s 中若使用 HostNetwork 或 ExternalName Service,通常不需要此步。

REMOTE_BROKER_CONTAINER_IP="${REMOTE_BROKER_CONTAINER_IP:-}"
REMOTE_BROKER_HOST_IP="${REMOTE_BROKER_HOST_IP:-}"

install_iptables_rules() {
  local container_ip="$1"
  local host_ip="$2"
  echo "[entrypoint] DNAT: ${container_ip}:10911/10912 -> ${host_ip}:10911/10912"
  iptables -t nat -A OUTPUT -d "${container_ip}" -p tcp --dport 10911 -j DNAT --to-destination "${host_ip}:10911"
  iptables -t nat -A OUTPUT -d "${container_ip}" -p tcp --dport 10912 -j DNAT --to-destination "${host_ip}:10912"
}

if [ -n "${REMOTE_BROKER_CONTAINER_IP}" ] && [ -n "${REMOTE_BROKER_HOST_IP}" ]; then
  if ! command -v iptables >/dev/null 2>&1; then
    echo "[entrypoint] WARNING: REMOTE_BROKER_*_IP set but iptables not available, skipping DNAT" >&2
  else
    OLDIFS="$IFS"
    IFS=','
    read -ra CONTAINER_IPS <<< "${REMOTE_BROKER_CONTAINER_IP}"
    read -ra HOST_IPS <<< "${REMOTE_BROKER_HOST_IP}"
    IFS="$OLDIFS"
    if [ "${#CONTAINER_IPS[@]}" -ne "${#HOST_IPS[@]}" ]; then
      echo "[entrypoint] ERROR: REMOTE_BROKER_CONTAINER_IP and REMOTE_BROKER_HOST_IP must have same count" >&2
      exit 1
    fi
    for i in "${!CONTAINER_IPS[@]}"; do
      install_iptables_rules "${CONTAINER_IPS[$i]}" "${HOST_IPS[$i]}"
    done
    echo "[entrypoint] iptables rules installed (${#CONTAINER_IPS[@]} pair(s))"
  fi
else
  echo "[entrypoint] no REMOTE_BROKER_*_IP set, skipping iptables DNAT"
fi

echo "[entrypoint] starting mqmirror"
exec java $JAVA_OPTS -jar /app/mqmirror.jar
