#!/usr/bin/env bash
set -euo pipefail
app_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
target="${1:-yvm@192.168.1.8}"
if [[ ! "$target" =~ ^[a-zA-Z0-9._@-]+$ || "$target" == -* ]]; then
  echo "Invalid SSH target" >&2
  exit 1
fi
if [[ ! -f "$app_dir/web/vendor/hls.min.js" ]]; then
  echo "Run npm ci and npm run build in openstream-irl first." >&2
  exit 1
fi
ssh "$target" 'mkdir -p ~/OpenStream-IRL'
rsync -a "$app_dir/self-hosted" "$app_dir/web" "$target:OpenStream-IRL/"
ssh "$target" bash -s <<'REMOTE'
set -euo pipefail
node -e 'if(Number(process.versions.node.split(".")[0])<22) process.exit(1)'
command -v tailscale >/dev/null
umask 077
mkdir -p ~/.config/openstream-irl ~/.local/share/openstream-irl ~/.config/systemd/user
node <<'JS'
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const cp = require('node:child_process');
const key = path.join(process.env.HOME, '.config/openstream-irl/admin-token');
if (!fs.existsSync(key)) fs.writeFileSync(key, crypto.randomBytes(32).toString('hex') + '\n', { mode: 0o600 });
const status = JSON.parse(cp.execFileSync('tailscale', ['serve', 'status', '--json']));
for (const [host, site] of Object.entries(status.Web || {})) {
  if (!host.endsWith(':8443')) continue;
  for (const [route, handler] of Object.entries(site.Handlers || {})) {
    if (route !== '/' || handler.Proxy !== 'http://127.0.0.1:8796') {
      throw new Error('Port 8443 already serves another app; refusing to overwrite it');
    }
  }
}
JS
cat > ~/.config/systemd/user/openstream-irl.service <<'UNIT'
[Unit]
Description=OpenStream-IRL private video relay
After=network-online.target

[Service]
Type=simple
WorkingDirectory=%h/OpenStream-IRL
Environment=HOST=127.0.0.1
Environment=PORT=8796
Environment=IRL_DATA_DIR=%h/.local/share/openstream-irl
Environment=CREATE_SESSION_TOKEN_FILE=%h/.config/openstream-irl/admin-token
ExecStart=/usr/bin/node %h/OpenStream-IRL/self-hosted/server.mjs
Restart=on-failure
RestartSec=3
UMask=0077
NoNewPrivileges=true

[Install]
WantedBy=default.target
UNIT
systemctl --user daemon-reload
systemctl --user enable --now openstream-irl.service
systemctl --user restart openstream-irl.service
if ! tailscale serve --bg --https=8443 http://127.0.0.1:8796; then
  sudo -n tailscale serve --bg --https=8443 http://127.0.0.1:8796
fi
systemctl --user is-active openstream-irl.service
printf 'Relay installed. Access key stays in ~/.config/openstream-irl/admin-token.\n'
REMOTE
