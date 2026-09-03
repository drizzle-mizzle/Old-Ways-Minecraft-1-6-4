# Minecraft 1.6.4 (Spigot 1.6.4-R2.1) — Towny + Heroes
#
# Java 8 обязательна: ядро 1.6.4 собрано под Java 6 и на 9+ ломается.
# Образ multi-arch (amd64/arm64), так что деплой на ARM-VPS тоже работает.
FROM eclipse-temurin:8-jre-jammy

LABEL org.opencontainers.image.title="Minecraft 1.6.4 — Towny + Heroes" \
      org.opencontainers.image.description="Legacy Bukkit-сервер 1.6.4: Towny 0.88.0.0, Heroes 1.5.5, WorldGuard, NoCheatPlus, Chairs" \
      org.opencontainers.image.version="1.0"

# Xms/Xmx переопределяются на docker run / в compose.
# Больше 4G старому однопоточному ядру давать бессмысленно: паузы GC растут быстрее пользы.
ENV JAVA_XMS=2G \
    JAVA_XMX=4G \
    JAVA_OPTS="" \
    TZ=UTC

RUN groupadd --system --gid 2000 minecraft \
 && useradd  --system --uid 2000 --gid 2000 --home-dir /data minecraft \
 && mkdir -p /opt/mc/skel /data \
 && chown -R minecraft:minecraft /opt/mc /data

# --- слой релиза: ядро и эталонная конфигурация -------------------------------
# Меняется только при обновлении сервера, поэтому лежит выше данных.
COPY --chown=minecraft:minecraft spigot-1.6.4-R2.1.jar  /opt/mc/server.jar
COPY --chown=minecraft:minecraft plugins/               /opt/mc/skel/plugins/
COPY --chown=minecraft:minecraft server.properties      /opt/mc/skel/server.properties
COPY --chown=minecraft:minecraft bukkit.yml spigot.yml help.yml permissions.yml wepif.yml \
                                                        /opt/mc/skel/
COPY --chown=minecraft:minecraft ops.txt white-list.txt banned-players.txt banned-ips.txt \
                                                        /opt/mc/skel/

COPY docker/entrypoint.sh   /usr/local/bin/entrypoint.sh
COPY docker/mc              /usr/local/bin/mc
COPY docker/healthcheck.sh  /usr/local/bin/mc-health
RUN chmod 0755 /usr/local/bin/entrypoint.sh /usr/local/bin/mc /usr/local/bin/mc-health

USER minecraft
WORKDIR /data
VOLUME ["/data"]
EXPOSE 25565/tcp

# SIGTERM запускает shutdown hook ядра: сохранение миров и корректная остановка.
# Дайте на это время — в compose стоит stop_grace_period: 120s.
STOPSIGNAL SIGTERM

# Порт открывается только после генерации мира, поэтому start-period щедрый.
HEALTHCHECK --interval=30s --timeout=5s --start-period=180s --retries=3 \
  CMD ["/usr/local/bin/mc-health"]

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
