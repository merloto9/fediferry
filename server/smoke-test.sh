#!/usr/bin/env bash
#
# Starts a FediFerry server image the way a host runs it and checks that it
# actually works: it comes up, answers, writes its database and media into the
# data volume as its own non-root user, and keeps them over a restart.
#
#     server/smoke-test.sh fediferry-server:smoke
#     server/smoke-test.sh fediferry-server:smoke ghcr.io/merloto9/fediferry-server:latest
#
# With a second image, the project is made by that one (the release before)
# and the image under test then starts on the same volume: the upgrade a host
# does with `docker compose pull`. If the older image cannot be pulled, that
# part is skipped with a warning.
#
# Needs docker and curl. Leaves nothing behind.

set -Eeuo pipefail

image=${1:?image to test}
previous=${2:-}

name=fediferry-smoke-$$
volume=fediferry-smoke-$$
png=$(mktemp)
trap 'docker rm -f "$name" >/dev/null 2>&1 || true; docker volume rm -f "$volume" >/dev/null 2>&1 || true; rm -f "$png"' EXIT

fail() {
    trap - ERR
    echo "::error::smoke test: $*"
    echo "--- server log ---"
    docker logs "$name" 2>&1 | tail -50 || true
    exit 1
}

# Any command that stops the script says which one, instead of ending it silently.
trap 'fail "line $LINENO stopped the script: $BASH_COMMAND"' ERR

# The token is the one indented line `project create` prints. The output is
# read whole first: a reader that stops early would break docker's pipe.
create_project() {
    local out
    out=$(docker run --rm -v "$volume:/data" "$1" project create "Smoke" 2>&1) || fail "$1 could not create a project: $out"
    awk '!t && /^    [A-Za-z0-9_.~-]+$/ { t = $1 } END { print t }' <<<"$out"
}

start() {
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker run -d --name "$name" -v "$volume:/data" -p 127.0.0.1::8080 "$1" >/dev/null || fail "$1 did not start"
    port=$(docker port "$name" 8080/tcp | sed -n '1s/.*://p')
    [ -n "$port" ] || fail "$1 publishes no port"
    base="http://127.0.0.1:$port"
    for _ in $(seq 1 60); do
        if curl -fsS "$base/api/v1/health" >/dev/null 2>&1; then return 0; fi
        if [ "$(docker inspect -f '{{.State.Running}}' "$name")" != "true" ]; then fail "$1 exited while starting"; fi
        sleep 1
    done
    fail "$1 did not answer /api/v1/health within 60 s"
}

# status of a call, body in $body
call() {
    local out
    out=$(curl -sS -w '\n%{http_code}' "$@") || fail "curl $* failed"
    status=${out##*$'\n'}
    body=${out%$'\n'*}
}

expect() {
    [ "$status" = "$1" ] || fail "$2: expected $1, got $status: $body"
}

token=""
if [ -n "$previous" ]; then
    if docker pull -q "$previous" >/dev/null 2>&1; then
        echo "Upgrade: making the project with $previous"
        token=$(create_project "$previous")
        [ -n "$token" ] || fail "$previous printed no project token"
        start "$previous"
        call -X PUT "$base/api/v1/settings/hashtag/%23before" -H "Authorization: Bearer $token" \
            -H 'Content-Type: application/json' -d '{"tag":"#before","sortOrder":0}'
        expect 200 "a setting written by $previous"
    else
        echo "::warning::smoke test: could not pull $previous; the upgrade is not checked"
    fi
fi

if [ -z "$token" ]; then
    token=$(create_project "$image")
    [ -n "$token" ] || fail "$image printed no project token"
fi

echo "Starting $image"
start "$image"

call "$base/api/v1/health"
expect 200 "health"
grep -q '"service":"fediferry"' <<<"$body" || fail "health does not name the service: $body"
echo "health: $body"

call "$base/api/v1/project" -H "Authorization: Bearer $token"
expect 200 "the project, with its token"
grep -q '"name":"Smoke"' <<<"$body" || fail "not the project made: $body"

call "$base/api/v1/project" -H "Authorization: Bearer not-a-token"
expect 401 "a wrong token"

if [ -n "$previous" ] && docker image inspect "$previous" >/dev/null 2>&1; then
    call "$base/api/v1/settings/hashtag" -H "Authorization: Bearer $token"
    expect 200 "settings after the upgrade"
    grep -q '#before' <<<"$body" || fail "the setting from before the upgrade is gone: $body"
fi

# The database is writable by the image's user.
call -X PUT "$base/api/v1/settings/hashtag/%23smoke" -H "Authorization: Bearer $token" \
    -H 'Content-Type: application/json' -d '{"tag":"#smoke","sortOrder":1}'
expect 200 "writing a setting"

# So is the media directory: a 1×1 PNG.
base64 -d >"$png" <<<'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=='
call -X POST "$base/api/v1/media" -H "Authorization: Bearer $token" -F "file=@$png;type=image/png"
case "$status" in 200|201) ;; *) fail "uploading a picture: expected 200 or 201, got $status: $body" ;; esac
asset=$(sed -n '1s/.*"id":"\([^"]*\)".*/\1/p' <<<"$body")
[ -n "$asset" ] || fail "the upload returned no asset id: $body"

# Everything is still there after a restart: it lives in the volume.
echo "Restarting"
docker stop -t 10 "$name" >/dev/null
start "$image"
call "$base/api/v1/settings/hashtag" -H "Authorization: Bearer $token"
expect 200 "settings after a restart"
grep -q '#smoke' <<<"$body" || fail "a setting did not survive the restart: $body"
call "$base/api/v1/media/$asset" -H "Authorization: Bearer $token" -o /dev/null
expect 200 "a picture after a restart"

echo "Smoke test passed: $image"
