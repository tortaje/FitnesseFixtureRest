#!/usr/bin/env bash
# Starts everything needed to run RestVaultSuite by hand from the FitNesse UI:
#   - a dev Vault in Docker, provisioned with the suite's secrets
#   - the stub endpoints (HTTP + mTLS) on fixed ports
#   - a FitNesse server whose Slim JVM gets the project classpath and the hvault/rest settings
#   - with --otel: a Grafana LGTM stack (Grafana, OTel collector, Tempo, Loki, Prometheus) receiving the fixtures'
#     traces, logs and JVM metrics through the fitnesse-otel standalone SDK
# Usage: scripts/fitnesse-ui.sh start [--otel] | stop
set -euo pipefail
cd "$(dirname "$0")/.."

VAULT_IMAGE=hashicorp/vault:1.18.3
VAULT_NAME=fitnesse-rest-vault
VAULT_PORT=${VAULT_PORT:-8200}
VAULT_TOKEN_DEV=it-root-token
HTTP_PORT=${HTTP_PORT:-9081}
TLS_PORT=${TLS_PORT:-9443}
FITNESSE_PORT=${FITNESSE_PORT:-9090}
RUN_DIR=target/fitnesse-ui
LGTM_IMAGE=grafana/otel-lgtm:0.36.0
LGTM_NAME=fitnesse-rest-lgtm
FITNESSE_OTEL_VERSION=1.0.0

stop() {
  for pid_file in "$RUN_DIR"/*.pid; do
    [ -f "$pid_file" ] || continue
    kill "$(cat "$pid_file")" 2>/dev/null || true
    rm -f "$pid_file"
  done
  docker rm -f "$VAULT_NAME" "$LGTM_NAME" >/dev/null 2>&1 || true
  echo "Stopped."
}

start() {
  local otel=false
  [ "${1:-}" = "--otel" ] && otel=true
  mkdir -p "$RUN_DIR"
  echo "Building test classes and classpath..."
  mvn -q test-compile dependency:build-classpath -Dmdep.outputFile="$RUN_DIR/classpath.txt"
  # slf4j-nop would clash with fitnesse's JUL provider (it only matters inside the shaded jar)
  DEPS=$(tr ':' '\n' < "$RUN_DIR/classpath.txt" | grep -v '/slf4j-nop-' | paste -sd: -)
  CP="$PWD/target/test-classes:$PWD/target/classes:$DEPS"
  SLIM_CP="$CP"

  if $otel; then
    echo "Starting Grafana LGTM ($LGTM_IMAGE) on ports 3000 / 4317 / 4318..."
    docker rm -f "$LGTM_NAME" >/dev/null 2>&1 || true
    docker run -d --name "$LGTM_NAME" -p 3000:3000 -p 4317:4317 -p 4318:4318 "$LGTM_IMAGE" >/dev/null
    mvn -q dependency:copy -Dartifact="io.github.tortaje:fitnesse-otel:$FITNESSE_OTEL_VERSION:jar:standalone" \
        -DoutputDirectory="$RUN_DIR"
    # exactly one OpenTelemetry on the Slim classpath: the standalone jar replaces the API and the test SDK jars
    OTEL_FREE=$(tr ':' '\n' <<< "$DEPS" | grep -v -e '/io/opentelemetry/' -e '/fitnesse-otel-' | paste -sd: -)
    SLIM_CP="$PWD/target/test-classes:$PWD/target/classes:$OTEL_FREE"
    SLIM_CP="$SLIM_CP:$PWD/$RUN_DIR/fitnesse-otel-$FITNESSE_OTEL_VERSION-standalone.jar"
    export TEST_RUN_ID="ui-$(date +%Y%m%d-%H%M%S)"
    # Grafana answers before the collector: wait for OTLP so the first run's telemetry is not dropped
    for _ in $(seq 1 90); do
      curl -sf -o /dev/null -X POST "http://localhost:4318/v1/traces" -H 'Content-Type: application/json' -d '{}' \
          && curl -sf "http://localhost:3000/api/health" >/dev/null && break
      sleep 1
    done
  fi

  echo "Starting Vault ($VAULT_IMAGE) on port $VAULT_PORT..."
  docker rm -f "$VAULT_NAME" >/dev/null 2>&1 || true
  docker run -d --name "$VAULT_NAME" -p "$VAULT_PORT:8200" --cap-add=IPC_LOCK \
      -e VAULT_DEV_ROOT_TOKEN_ID="$VAULT_TOKEN_DEV" "$VAULT_IMAGE" >/dev/null
  for _ in $(seq 1 30); do
    curl -sf "http://127.0.0.1:$VAULT_PORT/v1/sys/health" >/dev/null && break
    sleep 1
  done

  echo "Provisioning Vault and starting stubs on $HTTP_PORT (HTTP) and $TLS_PORT (mTLS)..."
  nohup java -cp "$CP" io.github.tortaje.rest.testsupport.ManualStubs \
      "http://127.0.0.1:$VAULT_PORT" "$VAULT_TOKEN_DEV" "$HTTP_PORT" "$TLS_PORT" > "$RUN_DIR/stubs.log" 2>&1 &
  echo $! > "$RUN_DIR/stubs.pid"
  for _ in $(seq 1 30); do
    grep -q "mTLS stub" "$RUN_DIR/stubs.log" && break
    sleep 1
  done
  grep -q "mTLS stub" "$RUN_DIR/stubs.log" || { cat "$RUN_DIR/stubs.log"; stop; exit 1; }

  # Slim runs in its own JVM: give it the full classpath and the settings the pages and fixtures read.
  SLIM_PROPS="-Dhvault.addr=http://127.0.0.1:$VAULT_PORT -Dhvault.token=$VAULT_TOKEN_DEV"
  SLIM_PROPS="$SLIM_PROPS -Drest.base_url=http://127.0.0.1:$HTTP_PORT -Drest.token_url=http://127.0.0.1:$HTTP_PORT/token"
  SLIM_PROPS="$SLIM_PROPS -Djava.util.logging.config.file=$PWD/src/test/resources/logging.properties"
  if $otel; then
    SLIM_PROPS="$SLIM_PROPS -Dotel.service.name=fitnesse-rest -Dotel.exporter.otlp.endpoint=http://localhost:4318"
    SLIM_PROPS="$SLIM_PROPS -Dotel.metric.export.interval=10000"
  fi
  echo "Starting FitNesse on port $FITNESSE_PORT..."
  nohup java -cp "$CP" \
      "-DCOMMAND_PATTERN=java -cp $SLIM_CP $SLIM_PROPS %m" \
      "-Drest.tls_port=$TLS_PORT" \
      fitnesseMain.FitNesseMain -p "$FITNESSE_PORT" -d src/test/fitnesse -r FitNesseRoot -e 0 -o \
      > "$RUN_DIR/fitnesse.log" 2>&1 &
  echo $! > "$RUN_DIR/fitnesse.pid"
  for _ in $(seq 1 30); do
    curl -sf "http://localhost:$FITNESSE_PORT/RestVaultSuite" >/dev/null && break
    sleep 1
  done

  echo
  echo "Ready: http://localhost:$FITNESSE_PORT/RestVaultSuite  (click Suite, or open a page and click Test)"
  if $otel; then
    echo "Grafana: http://localhost:3000  (Explore > Tempo / Loki, service.name=fitnesse-rest, test.run.id=$TEST_RUN_ID)"
  fi
  echo "Logs:  $RUN_DIR/stubs.log (one line per request with its traceparent)  $RUN_DIR/fitnesse.log"
  echo "Stop:  scripts/fitnesse-ui.sh stop"
}

case "${1:-}" in
  start) start "${2:-}" ;;
  stop) stop ;;
  *) echo "usage: $0 start [--otel] | stop" >&2; exit 2 ;;
esac
