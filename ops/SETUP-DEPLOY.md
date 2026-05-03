# Auto-Deploy Setup — One-Time Steps

After this is done, every merge to `main` auto-deploys to the VPS via GitHub
Actions. You won't need to run `./deploy.sh` again for normal updates.

## What gets set up

- A `deploy` user on the VPS (NOT root) with no password and no shell access
  except via a single SSH key.
- A read-only-on-the-laptop SSH keypair for GitHub Actions to use.
- Four GitHub repo secrets so the workflow can SSH to the VPS.
- Branch protection on `main` so nothing reaches production without a PR.

## Steps

### 1. Run the VPS setup script (one command)

From your laptop, with the repo checked out at the current commit:

```bash
scp ops/setup-deploy-user.sh root@89.167.115.83:/tmp/
ssh root@89.167.115.83 'bash /tmp/setup-deploy-user.sh'
```

Read the printed output. It tells you exactly what to paste where in the next
steps.

### 2. Generate the SSH keypair on your laptop

```bash
ssh-keygen -t ed25519 -N "" -C "github-actions-deploy" -f ./gh-actions-deploy
```

Two files appear: `gh-actions-deploy` (private) and `gh-actions-deploy.pub`
(public).

### 3. Authorize the public key on the VPS

```bash
cat gh-actions-deploy.pub | \
  ssh root@89.167.115.83 'cat >> /home/deploy/.ssh/authorized_keys'
```

### 4. Capture the VPS host key fingerprint

```bash
ssh-keyscan -t ed25519 -p 22 89.167.115.83
```

Copy the single line of output. You'll paste it into a GitHub secret next.

### 5. Add four GitHub repo secrets

Open <https://github.com/_owner_/github-store-backend/settings/secrets/actions>
(swap _owner_ for your real GitHub handle / org). Click **New repository
secret** four times:

| Secret name | Value |
|-------------|-------|
| `DEPLOY_HOST` | `89.167.115.83` |
| `DEPLOY_USER` | `deploy` |
| `DEPLOY_SSH_KEY` | the **entire contents** of the `gh-actions-deploy` file (including the `-----BEGIN OPENSSH PRIVATE KEY-----` and `-----END OPENSSH PRIVATE KEY-----` lines and every line in between) |
| `DEPLOY_KNOWN_HOSTS` | the line from step 4 |

### 6. Securely delete the laptop copy of the private key

```bash
rm gh-actions-deploy
```

After this command, the private key exists only inside GitHub Secrets
(encrypted at rest). If your laptop is stolen, the attacker cannot reach the
VPS via this key. If your GitHub account is compromised, you rotate by
re-running steps 1–6 with a fresh keypair and removing the old public key
from the VPS.

The public key (`gh-actions-deploy.pub`) is harmless — it stays on the VPS
inside `/home/deploy/.ssh/authorized_keys`.

### 7. Turn on branch protection for `main`

Open <https://github.com/_owner_/github-store-backend/settings/branches>.
Click **Add branch protection rule**.

- Branch name pattern: `main`
- ☑ Require a pull request before merging
- ☑ Block force pushes
- ☑ Block deletions
- ☑ Require linear history (optional but recommended)

After at least one successful workflow run, you can also turn on:

- ☑ Require status checks to pass before merging → select **Deploy to production**

That makes a green CI a precondition for merge. Until then, leave it off so
the first deploy can run.

### 8. Add CODEOWNERS

Create `.github/CODEOWNERS` if it does not exist:

```
# Workflows touch production credentials; review every change.
/.github/                          @<your-github-handle>

# Announcements are user-facing trusted content.
/src/main/resources/announcements/ @<your-github-handle>
```

Commit on a branch and merge via PR. Once merged, GitHub will require your
review for any future changes to those paths.

### 9. Test the workflow

Either:
- Push any small commit to `main` (e.g. fix a typo in a comment), OR
- Open the **Actions** tab → **Deploy to production** → **Run workflow** →
  pick the `main` branch → **Run workflow**.

Watch the run. It should print "Healthy after Ns" and finish green within ~3
minutes (longer for the first run because Docker layers aren't cached on
GitHub's runner yet).

Hit `https://api.github-store.org/v1/health` afterwards and confirm the
`announcements` count matches what's in the repo.

## What to do after this

- **Adding an announcement:** PR through the website admin panel → merge →
  workflow runs → live within ~10 min (Docker rebuild + 600s CDN TTL).
- **Editing or deleting an announcement:** same flow. No more manual deploys.
- **Break-glass deploy:** if GitHub Actions is down or you need to deploy a
  branch that isn't merged, the old `./deploy.sh 89.167.115.83` from your
  laptop still works.

## What changed for security

| Before | After |
|--------|-------|
| Laptop SSH'd to VPS as `root` | GitHub Actions SSHes to VPS as `deploy` (no shell, no sudo) |
| Deploy required your laptop to be online and run the script | Deploy runs in an ephemeral GitHub-hosted runner, no laptop needed |
| Private SSH key lived on the laptop | Private SSH key lives only inside GitHub Secrets (encrypted at rest) |
| Anyone with `root` access on the VPS could push code | Only `main` can deploy; `main` requires PR review |
| Manual step to remember after every PR merge | One-step PR merge |

## Rollback / recovery

- **A bad deploy takes the site down.** Either revert the offending commit on
  `main` (which auto-deploys the revert), or run `./deploy.sh 89.167.115.83`
  from your laptop with a known-good local checkout to manually restore.
- **GitHub credentials compromised.** Rotate immediately: delete the
  `DEPLOY_SSH_KEY` secret, regenerate via steps 1–6 with a new keypair,
  remove the old public key from `/home/deploy/.ssh/authorized_keys` on the
  VPS.
- **VPS host key changes (after reinstall, etc).** Re-run step 4 and update
  the `DEPLOY_KNOWN_HOSTS` secret. Old workflow runs will fail safely with a
  host-key mismatch rather than connect to a wrong host.
