#!/bin/sh
set -e
echo "containers:"
docker ps --format '{{.Names}} {{.Status}}'
IP=$(docker inspect -f '{{range.NetworkSettings.Networks}}{{.IPAddress}}{{end}}' aniga-backend)
echo "backend ip $IP"
python3 - <<PY
import urllib.request
ip = "$IP"
def get(path):
    try:
        with urllib.request.urlopen("http://%s:8080%s" % (ip, path), timeout=8) as r:
            body = r.read(80)
            print(path, r.status, r.headers.get("X-Content-Type-Options"), body[:60])
    except Exception as e:
        print(path, "ERR", e)
get("/api/health")
get("/js/core.js")
get("/js/console.js")
get("/api/me")
PY
echo "apk $(stat -c %s /home/ubuntu/aniga/download/aniga.apk)"
