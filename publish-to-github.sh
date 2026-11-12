#!/usr/bin/env bash
#
# publish-to-github.sh
#
# Log in to GitHub and publish THIS folder to a repository on YOUR account.
# Login uses the official GitHub CLI (gh) device/browser flow, so no token is
# ever written into the project. Safe to run again: it commits whatever has
# changed locally, creates the repo if needed, and pushes so GitHub matches
# your local folder exactly.
#
# Use this version inside a Codespace, on Linux, or on macOS:
#     bash ./publish-to-github.sh
#
set -euo pipefail
cd "$(dirname "$0")"

command -v git >/dev/null 2>&1 || { echo "git is not installed."; exit 1; }
command -v gh  >/dev/null 2>&1 || { echo "GitHub CLI (gh) is not installed: https://cli.github.com"; exit 1; }

[ -d .git ] || { git init >/dev/null; git branch -M main; }

# --- Log in to GitHub ---
# Probe with an API call, not `gh auth status`: status returns nonzero when any
# stored account has an expired token, even if the active account is fine.
user=$(gh api user --jq .login 2>/dev/null || true)
if [ -z "$user" ]; then
    echo "Opening GitHub login..."
    gh auth login
    user=$(gh api user --jq .login 2>/dev/null || true)
    [ -n "$user" ] || { echo "Could not read your GitHub username."; exit 1; }
fi
echo "Logged in as: $user"
read -rp "Publish to this account? Press Enter to accept, or type 'switch' to use another account: " ans
if [ "$ans" = "switch" ]; then
    gh auth login
    user=$(gh api user --jq .login)
    echo "Now logged in as: $user"
fi
gh auth setup-git >/dev/null 2>&1 || true

ensure_git_identity() {
    local git_name git_email
    git_name=$(git config user.name 2>/dev/null || true)
    git_email=$(git config user.email 2>/dev/null || true)
    if [ -z "$git_name" ]; then
        git config user.name "$user"
        echo "Configured local git user.name as $user."
    fi
    if [ -z "$git_email" ]; then
        git config user.email "${user}@users.noreply.github.com"
        echo "Configured local git user.email as ${user}@users.noreply.github.com."
    fi
}

normalize_visibility() {
    local value
    value=$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
    case "$value" in
        ""|public) printf 'public\n' ;;
        private) printf 'private\n' ;;
        *) return 1 ;;
    esac
}

# --- Repository name and visibility ---
default=$(basename "$PWD")
read -rp "Repository name [$default]: " repo; repo=${repo:-$default}
while true; do
    read -rp "Visibility - type 'private' or 'public' [public]: " vis
    if vis=$(normalize_visibility "$vis"); then
        break
    fi
    echo "Invalid visibility. Type 'public', 'private', or press Enter for public."
done
slug="$user/$repo"

# --- Commit pending local changes so GitHub matches local exactly ---
ensure_git_identity
git add -A
if ! git diff --cached --quiet; then
    read -rp "Commit message [Update HubShark]: " msg; msg=${msg:-Update HubShark}
    git commit -m "$msg" >/dev/null
    echo "Committed local changes."
else
    echo "Nothing new to commit; the working folder already matches the last commit."
fi

branch=$(git rev-parse --abbrev-ref HEAD)
[ "$branch" = "HEAD" ] && { git branch -M main; branch=main; }

# --- Create the repo on your account if it does not exist yet ---
if ! gh repo view "$slug" >/dev/null 2>&1; then
    echo "Creating $vis repository $slug ..."
    gh repo create "$slug" "--$vis"
fi

# --- Point a dedicated remote at it and push ---
url="https://github.com/$slug.git"
if git remote | grep -qx mine; then git remote set-url mine "$url"; else git remote add mine "$url"; fi
echo "Pushing $branch to $slug ..."
git push -u mine "$branch"

echo ""
echo "  Done. Your project is live at: https://github.com/$slug"
