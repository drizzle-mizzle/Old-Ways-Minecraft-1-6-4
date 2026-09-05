# Сервис авторизации и скинов Old Ways
FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    OW_DB=/data/auth.sqlite3 \
    OW_SKINS=/data/skins

WORKDIR /srv

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt \
 && useradd --system --uid 2001 --home-dir /data auth \
 && mkdir -p /data && chown auth:auth /data

COPY --chown=auth:auth app.py .
COPY --chown=auth:auth default_skin.png /opt/auth/default_skin.png

USER auth
VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:8080/healthz',timeout=3).status==200 else 1)"

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8080"]
