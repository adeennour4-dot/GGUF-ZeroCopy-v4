#!/data/data/com.termux/files/usr/bin/bash
# =============================================================================
# GGUF ZeroCopy v4 — Termux Deploy Script
# =============================================================================
# What this does:
#   1. Installs required packages (git, gh, curl, jq, openssh)
#   2. Authenticates with GitHub CLI
#   3. Creates a new GitHub repo (or pushes to an existing one)
#   4. Pushes the project code
#   5. Waits for the GitHub Actions build to complete
#   6. Downloads the APK artifact to your device
#   7. (Optional) installs the APK via adb or shares a download link
#
# Usage:
#   chmod +x termux-deploy.sh
#   ./termux-deploy.sh
#
# Required: A GitHub account. The script will guide you through login.
# =============================================================================

set -euo pipefail

# ─── Colours ──────────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

info()    { echo -e "${CYAN}[INFO]${NC} $*"; }
success() { echo -e "${GREEN}[OK]${NC}   $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $*"; }
error()   { echo -e "${RED}[ERR]${NC}  $*"; exit 1; }
step()    { echo -e "\n${BOLD}${CYAN}══ $* ══${NC}"; }

# ─── Configuration ────────────────────────────────────────────────────────────
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_REPO_NAME="GGUF-ZeroCopy-v4"
WORKFLOW_FILE=".github/workflows/build.yml"
ARTIFACT_NAME="GGUF-ZeroCopy-v4-debug"
APK_OUT_DIR="$HOME/storage/downloads"   # Termux shared downloads folder

# ─── 1. Check we're in Termux ────────────────────────────────────────────────
step "Environment check"
if [ ! -d "/data/data/com.termux" ]; then
    warn "Not running in Termux. Continuing anyway (Linux mode)."
fi
info "Project directory: $PROJECT_DIR"

# ─── 2. Install dependencies ─────────────────────────────────────────────────
step "Installing required packages"
pkg update -y -q 2>/dev/null || apt-get update -q
for pkg in git gh curl jq openssh zip unzip; do
    if ! command -v "$pkg" &>/dev/null; then
        info "Installing $pkg…"
        pkg install -y "$pkg" 2>/dev/null || apt-get install -y "$pkg" || warn "Could not install $pkg"
    else
        success "$pkg already installed"
    fi
done

# ─── 3. GitHub CLI authentication ────────────────────────────────────────────
step "GitHub authentication"
if ! gh auth status &>/dev/null; then
    echo ""
    echo -e "${YELLOW}You need to authenticate with GitHub.${NC}"
    echo "Choose one of:"
    echo "  1) Browser login (opens GitHub in your browser)"
    echo "  2) Personal Access Token (PAT)"
    read -r -p "Choice [1/2]: " AUTH_CHOICE
    case "$AUTH_CHOICE" in
        2)
            echo ""
            echo "Create a PAT at: https://github.com/settings/tokens/new"
            echo "Required scopes: repo, workflow, write:packages"
            read -r -p "Paste your PAT: " GH_PAT
            echo "$GH_PAT" | gh auth login --with-token
            ;;
        *)
            gh auth login --web --git-protocol https || \
            gh auth login --git-protocol https
            ;;
    esac
else
    GITHUB_USER=$(gh api user --jq '.login' 2>/dev/null || echo "unknown")
    success "Already authenticated as: $GITHUB_USER"
fi

GITHUB_USER=$(gh api user --jq '.login')
info "GitHub user: $GITHUB_USER"

# ─── 4. Repo name ────────────────────────────────────────────────────────────
step "Repository setup"
read -r -p "Repo name [$DEFAULT_REPO_NAME]: " REPO_NAME
REPO_NAME="${REPO_NAME:-$DEFAULT_REPO_NAME}"
REMOTE_URL="https://github.com/$GITHUB_USER/$REPO_NAME.git"

# ─── 5. Init git if needed ───────────────────────────────────────────────────
cd "$PROJECT_DIR"

if [ ! -d ".git" ]; then
    info "Initialising git repository…"
    git init
    git add -A
    git commit -m "feat: GGUF ZeroCopy v4 — chat UI, abort, benchmark, model info, KV cache stats"
else
    info "Git repo already initialised."
    git add -A
    if ! git diff --cached --quiet; then
        git commit -m "chore: update GGUF ZeroCopy v4"
    else
        info "Nothing new to commit."
    fi
fi

# ─── 6. Create or use existing GitHub repo ───────────────────────────────────
if gh repo view "$GITHUB_USER/$REPO_NAME" &>/dev/null; then
    warn "Repo $GITHUB_USER/$REPO_NAME already exists — will push to it."
else
    info "Creating public repo: $GITHUB_USER/$REPO_NAME"
    gh repo create "$REPO_NAME" \
        --public \
        --description "GGUF ZeroCopy v4 — on-device LLM inference for Android" \
        --source=. \
        --remote=origin \
        --push
fi

# ─── 7. Set remote and push ──────────────────────────────────────────────────
step "Pushing code to GitHub"
if ! git remote get-url origin &>/dev/null; then
    git remote add origin "$REMOTE_URL"
fi

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "main")
[ "$BRANCH" = "HEAD" ] && { git checkout -b main; BRANCH="main"; }

info "Pushing branch '$BRANCH' to origin…"
git push -u origin "$BRANCH" --force-with-lease 2>/dev/null || \
git push -u origin "$BRANCH" --force

success "Code pushed → $REMOTE_URL"

# ─── 8. Trigger GitHub Actions ───────────────────────────────────────────────
step "Triggering GitHub Actions build"

# Ask if user wants a release
read -r -p "Create a GitHub Release? [y/N]: " CREATE_RELEASE
RELEASE_TAG=""
if [[ "$CREATE_RELEASE" =~ ^[Yy]$ ]]; then
    read -r -p "Release tag (e.g. v4.0.0): " RELEASE_TAG
fi

info "Triggering workflow: $WORKFLOW_FILE"
if [ -n "$RELEASE_TAG" ]; then
    gh workflow run "$WORKFLOW_FILE" \
        --repo "$GITHUB_USER/$REPO_NAME" \
        --ref "$BRANCH" \
        --field "release_tag=$RELEASE_TAG"
else
    gh workflow run "$WORKFLOW_FILE" \
        --repo "$GITHUB_USER/$REPO_NAME" \
        --ref "$BRANCH"
fi

success "Workflow triggered!"
echo ""
echo -e "  🔗 Watch live: ${CYAN}https://github.com/$GITHUB_USER/$REPO_NAME/actions${NC}"

# ─── 9. Wait for build ───────────────────────────────────────────────────────
step "Waiting for build to complete (15–25 min typical)"
info "Polling every 30 seconds… Press Ctrl-C to stop waiting (APK won't be downloaded)."

sleep 15   # Give Actions a moment to register the run

# Get run ID
RUN_ID=""
for i in $(seq 1 10); do
    RUN_ID=$(gh run list \
        --repo "$GITHUB_USER/$REPO_NAME" \
        --workflow "$WORKFLOW_FILE" \
        --limit 1 \
        --json databaseId \
        --jq '.[0].databaseId' 2>/dev/null || echo "")
    [ -n "$RUN_ID" ] && break
    sleep 10
done

if [ -z "$RUN_ID" ]; then
    warn "Could not get run ID. Open Actions manually:"
    echo "  https://github.com/$GITHUB_USER/$REPO_NAME/actions"
    exit 0
fi

info "Run ID: $RUN_ID"
echo -e "  🔗 ${CYAN}https://github.com/$GITHUB_USER/$REPO_NAME/actions/runs/$RUN_ID${NC}"

# Poll until done
STATUS=""
CONCLUSION=""
ELAPSED=0
while true; do
    JSON=$(gh run view "$RUN_ID" \
        --repo "$GITHUB_USER/$REPO_NAME" \
        --json status,conclusion 2>/dev/null || echo '{"status":"unknown","conclusion":""}')
    STATUS=$(echo "$JSON" | jq -r '.status')
    CONCLUSION=$(echo "$JSON" | jq -r '.conclusion')

    printf "\r  Status: %-20s  Elapsed: %dm%02ds   " "$STATUS" $((ELAPSED/60)) $((ELAPSED%60))

    if [ "$STATUS" = "completed" ]; then
        echo ""
        break
    fi
    sleep 30
    ELAPSED=$((ELAPSED + 30))
done

if [ "$CONCLUSION" != "success" ]; then
    error "Build $CONCLUSION. Check logs: https://github.com/$GITHUB_USER/$REPO_NAME/actions/runs/$RUN_ID"
fi

success "Build completed successfully in ${ELAPSED}s!"

# ─── 10. Download APK ────────────────────────────────────────────────────────
step "Downloading APK"

# Ensure output dir exists
mkdir -p "$APK_OUT_DIR" 2>/dev/null || APK_OUT_DIR="$HOME"

info "Downloading artifact '$ARTIFACT_NAME' to $APK_OUT_DIR…"
DOWNLOAD_DIR=$(mktemp -d)

gh run download "$RUN_ID" \
    --repo "$GITHUB_USER/$REPO_NAME" \
    --name "$ARTIFACT_NAME" \
    --dir "$DOWNLOAD_DIR"

# Find the APK
APK_FILE=$(find "$DOWNLOAD_DIR" -name "*.apk" | head -1)
if [ -z "$APK_FILE" ]; then
    error "APK not found in downloaded artifact!"
fi

APK_DEST="$APK_OUT_DIR/$(basename "$APK_FILE")"
cp "$APK_FILE" "$APK_DEST"
rm -rf "$DOWNLOAD_DIR"

success "APK saved to: $APK_DEST"
echo ""
echo -e "  📦 ${BOLD}$(basename "$APK_DEST")${NC}  ($(du -sh "$APK_DEST" | cut -f1))"

# ─── 11. Install options ─────────────────────────────────────────────────────
step "Install options"
echo "  1) Open with file manager / installer (recommended for Termux)"
echo "  2) adb install (requires adb over network or USB)"
echo "  3) Skip — APK is in Downloads"
read -r -p "Choice [1/2/3]: " INSTALL_CHOICE

case "$INSTALL_CHOICE" in
    1)
        termux-open "$APK_DEST" 2>/dev/null || \
        xdg-open "$APK_DEST" 2>/dev/null || \
        info "Open this file manually: $APK_DEST"
        ;;
    2)
        read -r -p "Device IP:PORT for adb (e.g. 192.168.1.100:5555): " ADB_TARGET
        if command -v adb &>/dev/null; then
            adb connect "$ADB_TARGET"
            adb install "$APK_DEST" && success "APK installed via adb!" || warn "adb install failed"
        else
            pkg install android-tools -y 2>/dev/null || apt-get install -y android-tools-adb
            adb connect "$ADB_TARGET"
            adb install "$APK_DEST" && success "APK installed via adb!" || warn "adb install failed"
        fi
        ;;
    *)
        info "APK is in: $APK_DEST"
        info "Copy to your device or open with your file manager."
        ;;
esac

# ─── Done ─────────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}${BOLD}════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}${BOLD}  GGUF ZeroCopy v4 deployment complete!${NC}"
echo -e "${GREEN}${BOLD}════════════════════════════════════════════════════════${NC}"
echo ""
echo -e "  🏠 Repo:     ${CYAN}https://github.com/$GITHUB_USER/$REPO_NAME${NC}"
echo -e "  ⚙ Actions:  ${CYAN}https://github.com/$GITHUB_USER/$REPO_NAME/actions${NC}"
[ -n "$RELEASE_TAG" ] && \
echo -e "  📦 Release:  ${CYAN}https://github.com/$GITHUB_USER/$REPO_NAME/releases/tag/$RELEASE_TAG${NC}"
echo -e "  📱 APK:      ${BOLD}$APK_DEST${NC}"
echo ""
