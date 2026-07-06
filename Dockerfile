FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /workspace

COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY config config
COPY modules modules

RUN chmod +x ./gradlew && \
    ./gradlew --no-daemon test lint build :modules:bot-app:installDist && \
    mkdir -p modules/bot-app/build/install/bot-app/config && \
    cp config/volume-flow-composite-current.json modules/bot-app/build/install/bot-app/config/

FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends curl ca-certificates && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd --system bybit-trader && \
    useradd --system --gid bybit-trader --home-dir /opt/bybit-trader bybit-trader && \
    mkdir -p /opt/bybit-trader /data && \
    chown -R bybit-trader:bybit-trader /opt/bybit-trader /data

WORKDIR /opt/bybit-trader

COPY --from=builder --chown=bybit-trader:bybit-trader /workspace/modules/bot-app/build/install/bot-app/ /opt/bybit-trader/

USER bybit-trader

ENV BOT_API_HOST=0.0.0.0
ENV BOT_API_PORT=8080
ENV BOT_DATABASE_PATH=/data/bybit-trader.sqlite
ENV BOT_VOLUME_FLOW_COMPOSITE_CONFIG_PATH=/opt/bybit-trader/config/volume-flow-composite-current.json
ENV BOT_STRATEGY_PROFILE_STATE_PATH=/data/strategy-profile-current.txt

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -fsS http://127.0.0.1:${BOT_API_PORT}/health || exit 1

ENTRYPOINT ["/opt/bybit-trader/bin/bot-app"]
