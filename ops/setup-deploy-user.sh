#!/usr/bin/env bash
#
# One-time VPS setup for the GitHub Actions deploy user. Run as root on the
# production VPS. Idempotent -- safe to re-run.
#
# Usage:
#     scp ops/setup-deploy-user.sh root@<VPS_IP>:/tmp/
#     ssh root@<VPS_IP> 'bash /tmp/setup-deploy-user.sh'
#
set -euo pipefail

DEPLOY_USER="deploy"
DEPLOY_HOME="/home/$DEPLOY_USER"
APP_DIR="/opt/github-store-backend"

echo "==> Creating $DEPLOY_USER user (if missing)"
if id "$DEPLOY_USER" >/dev/null 2>&1; then
  echo "    user already exists"
else
  adduser --disabled-password --gecos "" "$DEPLOY_USER"
fi

echo "==> Adding $DEPLOY_USER to docker group"
usermod -aG docker "$DEPLOY_USER"

echo "==> Ensuring $APP_DIR exists and is owned by $DEPLOY_USER"
mkdir -p "$APP_DIR"
chown -R "$DEPLOY_USER:$DEPLOY_USER" "$APP_DIR"

echo "==> Preparing $DEPLOY_HOME/.ssh"
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$DEPLOY_HOME/.ssh"
touch "$DEPLOY_HOME/.ssh/authorized_keys"
chmod 600 "$DEPLOY_HOME/.ssh/authorized_keys"
chown "$DEPLOY_USER:$DEPLOY_USER" "$DEPLOY_HOME/.ssh/authorized_keys"

VPS_IP="$(curl -fsS https://api.ipify.org || echo '<VPS_IP>')"

cat <<EOF

==============================================================
  VPS-side setup is done. Now do these steps on YOUR LAPTOP:
==============================================================

(1) Generate a deploy-only SSH keypair on your laptop:

    ssh-keygen -t ed25519 -N "" -C "github-actions-deploy" -f ./gh-actions-deploy

    This creates two files in your current directory:
        gh-actions-deploy        <- PRIVATE key (goes into a GitHub secret)
        gh-actions-deploy.pub    <- PUBLIC key (paste onto VPS in step 2)

(2) Copy the PUBLIC key onto this VPS as the $DEPLOY_USER's authorized_keys:

    cat gh-actions-deploy.pub | \\
      ssh root@$VPS_IP 'cat >> $DEPLOY_HOME/.ssh/authorized_keys'

(3) Get the VPS's SSH host key fingerprint (this exact line):

    ssh-keyscan -t ed25519 -p 22 $VPS_IP

    Save the output line as a GitHub secret named DEPLOY_KNOWN_HOSTS.

(4) Open https://github.com/<owner>/github-store-backend/settings/secrets/actions
    Click "New repository secret" four times, one per secret:

      Name: DEPLOY_HOST            Value: $VPS_IP
      Name: DEPLOY_USER            Value: $DEPLOY_USER
      Name: DEPLOY_SSH_KEY         Value: <entire content of gh-actions-deploy>
                                          (include BEGIN/END lines, all newlines)
      Name: DEPLOY_KNOWN_HOSTS     Value: <the line from step 3>

(5) Securely delete the laptop copy of the private key:

    rm gh-actions-deploy

    The key is now only on GitHub (encrypted) and the VPS (public half).
    There is no copy on your laptop that an attacker could steal.

(6) Enable branch protection on main at:
    https://github.com/<owner>/github-store-backend/settings/branches

      Branch name pattern: main
      [x] Require a pull request before merging
      [x] Block force pushes
      [x] Block deletions
      [x] Require linear history (optional but recommended)

(7) Test by pushing any small change to main (or click
    "Run workflow" in the Actions tab to deploy without a code change).
    Watch the workflow run; it should finish with "Healthy after Ns".

After step 7 succeeds, your local deploy.sh is no longer required for
normal use -- every merge to main auto-deploys. Keep deploy.sh as a
break-glass tool for when GitHub Actions is down.
==============================================================
EOF
