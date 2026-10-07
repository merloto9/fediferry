# FediFerry server

Keeps a project's library, drafts, review and channels in one place, so
several phones can work on them together. One server hosts any number of
projects; they never see each other. Each project is opened with a project
token, entered once on every phone (Settings → FediFerry server).

## Run it

With Docker, from the repository root:

```sh
docker compose -f server/docker-compose.yml up -d --build
docker compose -f server/docker-compose.yml exec fediferry /opt/fediferry/bin/fediferry project create "Memes"
```

The second command prints the project token once. Keep it; a lost one is
replaced with `fediferry token add Memes`.

Without Docker (JDK 17+):

```sh
./gradlew :server:installDist
server/build/install/fediferry/bin/fediferry project create "Memes" --data /var/lib/fediferry
server/build/install/fediferry/bin/fediferry serve --data /var/lib/fediferry --port 8080
```

| Setting | Flag | Environment | Default |
|---|---|---|---|
| Data directory (database, media) | `--data` | `FEDIFERRY_DATA` | `./data` |
| Port | `--port` | `FEDIFERRY_PORT` | `8080` |
| Listen address | `--host` | `FEDIFERRY_HOST` | `0.0.0.0` |

With Docker Compose, `FEDIFERRY_HOST_PORT` picks the port on the host and
`FEDIFERRY_BIND=127.0.0.1` keeps it to the host (behind a reverse proxy).

## Projects and tokens

```sh
fediferry project create "Name"      # prints the first token
fediferry project list
fediferry project delete "Name"      # asks for the name again; --yes skips that
fediferry token add "Name" --label partner
fediferry token list "Name"          # ids and labels, never the tokens
fediferry token revoke "Name" <token id>
```

## Reaching it from phones

From outside your home network, put the server behind a reverse proxy with
TLS, e.g. Caddy (`fediferry.example.org { reverse_proxy localhost:8080 }`),
or reach it through a VPN such as Tailscale. Over plain HTTP the project token
travels in the clear, so release builds of the app only talk HTTPS.

In the home network, Android 17 asks once for permission to reach local
devices when the app connects to a private address.

## Backup

Everything lives in the data directory: `fediferry.db` (SQLite) and, from the
library milestone on, `media/`. Stop the server or use `sqlite3 .backup`, then
copy the directory.

## Development

`./gradlew :server:test` runs the API tests. `./gradlew :server:run` serves
from `server/data`. The Android emulator reaches the development machine at
`http://10.0.2.2:<port>`.
