#!/usr/bin/env bash
# Mentora launcher.
#   ./run.sh          build and start everything (database, backend, frontend)
#   ./run.sh restart  rebuild and restart (same as start; picks up code and .env changes)
#   ./run.sh stop     stop the containers (data is kept)
#   ./run.sh logs     follow backend logs
#   ./run.sh status   show container status
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"
# Git Bash on Windows: use the Windows-style path (C:/...) so Docker/Node resolve the same path casing
if pwd -W >/dev/null 2>&1; then cd "$(pwd -W)"; fi
SRC="$(pwd)"

cmd="${1:-start}"

if ! command -v docker >/dev/null 2>&1; then
  echo "Docker is not installed or not on PATH." >&2; exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker is not running. Start Docker Desktop and try again." >&2; exit 1
fi

[ -f .env ] || { cp .env.example .env; echo "Created .env from .env.example (add GEMINI_API_KEY there to enable AI features)."; }

# OneDrive turns project files into cloud placeholders that Docker cannot read ("invalid file request").
# When the project lives in OneDrive, build from a plain local copy. The compose project name stays
# "mentor", so the existing database/storage volumes are reused.
COMPOSE=(docker compose -p mentor)
case "$SRC" in
  *[Oo]ne[Dd]rive*)
    BUILD_DIR="${MENTORA_BUILD_DIR:-$HOME/.mentora-build}"
    if [ "$cmd" = "start" ] || [ "$cmd" = "restart" ]; then
      echo "Project is inside OneDrive: syncing a build copy to $BUILD_DIR"
      rm -rf "$BUILD_DIR"; mkdir -p "$BUILD_DIR"
      tar -C "$SRC" --exclude=node_modules --exclude=target --exclude=dist --exclude=storage --exclude=.git --exclude='*.log' -cf - . | tar -C "$BUILD_DIR" -xf -
    fi
    [ -d "$BUILD_DIR" ] || { echo "No build copy yet. Run ./run.sh first." >&2; exit 1; }
    COMPOSE=(docker compose -p mentor --project-directory "$BUILD_DIR")
    ;;
esac

case "$cmd" in
  start|restart)
    "${COMPOSE[@]}" up --build -d
    echo "Waiting for the backend to become healthy..."
    state=""
    for _ in $(seq 1 60); do
      state="$("${COMPOSE[@]}" ps --format '{{.Service}} {{.Health}}' | awk '$1=="backend"{print $2}')"
      [ "$state" = "healthy" ] && break
      sleep 3
    done
    if [ "$state" != "healthy" ]; then
      echo "Backend did not become healthy. Check: ./run.sh logs" >&2; exit 1
    fi
    port="$(grep -E '^BACKEND_PORT=' .env 2>/dev/null | tail -1 | cut -d= -f2)"
    APP_URL="http://localhost:3000"
    cat <<EOF

==========================================================
  Mentora is running. Open it here (Ctrl+Click the link):

      $APP_URL

==========================================================
  API:      http://localhost:${port:-8081}
  Database: localhost:5433

Demo logins:
  admin@mentora.edu     / Admin@123
  mentor1@mentora.edu   / Mentor@123   (Dr. Sunitha K, 30 students)
  mentor2@mentora.edu   / Mentor@123   (Ms. Nivedita G. Y., 30 students)
  1rn23is003@mentora.edu / Student@123

Stop with: ./run.sh stop     (set NO_OPEN=1 to skip auto-opening the browser)
EOF
    # open the app in the default browser (Windows / macOS / Linux)
    if [ -z "${NO_OPEN:-}" ]; then
      { cmd.exe /c start "" "$APP_URL" || open "$APP_URL" || xdg-open "$APP_URL"; } >/dev/null 2>&1 || true
    fi
    ;;
  stop)   "${COMPOSE[@]}" stop ;;
  logs)   "${COMPOSE[@]}" logs -f backend ;;
  status) "${COMPOSE[@]}" ps ;;
  *) echo "Usage: ./run.sh [start|restart|stop|logs|status]" >&2; exit 1 ;;
esac
