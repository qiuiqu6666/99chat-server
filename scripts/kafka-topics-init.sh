#!/usr/bin/env bash
# 同机 Kafka Topic 初始化（99chat IM 消息归档）
set -euo pipefail

KAFKA_HOME="${KAFKA_HOME:-/www/server/kafka/kafka_2.13-3.6.0}"
KAFKA_TOPICS="${KAFKA_HOME}/bin/kafka-topics.sh"
BS="${KAFKA_BOOTSTRAP:-127.0.0.1:9092}"

if [[ ! -x "$KAFKA_TOPICS" ]]; then
  echo "kafka-topics.sh not found: $KAFKA_TOPICS" >&2
  exit 1
fi

create_if_missing() {
  local topic=$1
  shift
  if "$KAFKA_TOPICS" --bootstrap-server "$BS" --list | grep -qx "$topic"; then
    echo "exists: $topic"
  else
    "$KAFKA_TOPICS" --create --bootstrap-server "$BS" --topic "$topic" "$@"
    echo "created: $topic"
  fi
}

create_if_missing chat99.im.after-send \
  --partitions 8 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=1

create_if_missing chat99.im.archive.dlq \
  --partitions 4 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --config min.insync.replicas=1

create_if_missing chat99.im.push.dlq \
  --partitions 8 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --config min.insync.replicas=1

create_if_missing chat99.im.group-recall \
  --partitions 4 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=1

create_if_missing chat99.im.rest.jobs \
  --partitions 4 \
  --replication-factor 1 \
  --config retention.ms=604800000 \
  --config min.insync.replicas=1

create_if_missing chat99.im.rest.jobs.dlq \
  --partitions 4 \
  --replication-factor 1 \
  --config retention.ms=2592000000 \
  --config min.insync.replicas=1

echo "--- describe chat99.im.after-send ---"
"$KAFKA_TOPICS" --bootstrap-server "$BS" --describe --topic chat99.im.after-send
