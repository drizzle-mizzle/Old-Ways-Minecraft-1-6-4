#!/usr/bin/env bash
# Точка входа контейнера Minecraft 1.6.4.
#
# Разделение ответственности:
#   /opt/mc  — релиз: ядро, jar-ники плагинов, эталонные конфиги. Живёт в образе.
#   /data    — состояние: миры, конфиги плагинов, банк/города/классы. Живёт в томе.
set -euo pipefail

SKEL=/opt/mc/skel
DATA=/data
FIFO=/tmp/console

log() { printf '[entrypoint] %s\n' "$*"; }
count() { set +o pipefail; ls -1 $1 2>/dev/null | wc -l; set -o pipefail; }

# --- первый запуск: развернуть эталонную конфигурацию в пустой том ------------
if [ ! -f "$DATA/server.properties" ]; then
  log "том $DATA пуст — разворачиваю конфигурацию из образа"
  cp -a "$SKEL/." "$DATA/"
else
  log "найдена существующая конфигурация в $DATA — сохраняю её"
fi

# --- jar-ники плагинов всегда берутся из образа -------------------------------
# Так обновление сервера = docker pull + restart, а настройки и города остаются.
# Осторожно: удалённый из образа плагин в старом томе не исчезнет, уберите вручную.
mkdir -p "$DATA/plugins/Heroes/skills"
cp -f "$SKEL"/plugins/*.jar "$DATA/plugins/" 2>/dev/null || true
cp -f "$SKEL"/plugins/Heroes/skills/*.jar "$DATA/plugins/Heroes/skills/" 2>/dev/null || true
log "плагинов: $(count "$DATA/plugins/*.jar"), скиллов Heroes: $(count "$DATA/plugins/Heroes/skills/*.jar")"

# --- канал в консоль сервера --------------------------------------------------
# Без открытого stdin консольный поток CraftBukkit читает EOF в цикле и жжёт ядро.
# FIFO решает это и заодно даёт способ слать команды: docker exec <c> mc "say привет"
rm -f "$FIFO"
mkfifo "$FIFO"
sleep infinity > "$FIFO" &   # держатель, чтобы FIFO не закрывался после каждой записи

log "старт: Xms=$JAVA_XMS Xmx=$JAVA_XMX TZ=$TZ"

# --- запуск и корректная остановка -------------------------------------------
# Соблазнительно написать `exec java`, чтобы ядро получало SIGTERM напрямую.
# Так делать нельзя: shutdown-хук CraftBukkit 1.6.4 обрывается на первом же
# плагине, JVM умирает с кодом 143, и миры остаются несохранёнными — проверено.
# Рабочий путь один — та же команда stop, что и в живой консоли: она проходит
# полный цикл (Saving players / Saving worlds / Saving chunks) и даёт код 0.
java \
  -Xms"$JAVA_XMS" -Xmx"$JAVA_XMX" \
  -XX:+UseConcMarkSweepGC -XX:+UseParNewGC \
  -Djline.terminal=jline.UnsupportedTerminal \
  -Dfile.encoding=UTF-8 \
  ${JAVA_OPTS} \
  -jar /opt/mc/server.jar nogui < "$FIFO" &
JAVA_PID=$!

on_signal() {
  log "получен сигнал остановки — отправляю stop в консоль сервера"
  printf 'stop\n' > "$FIFO" 2>/dev/null || kill -TERM "$JAVA_PID" 2>/dev/null || true
}
trap on_signal TERM INT

# wait прерывается пришедшим сигналом и возвращает 128+n, поэтому ждём в цикле,
# пока процесс действительно не завершится.
set +e
RC=0
while kill -0 "$JAVA_PID" 2>/dev/null; do
  wait "$JAVA_PID"
  RC=$?
done
log "сервер остановлен, код $RC"
exit "$RC"
