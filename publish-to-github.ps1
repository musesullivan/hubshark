#Requires -Version 5
<#
    publish-to-github.ps1

    One step: log in to GitHub and publish THIS folder to a repository on YOUR
    account. Login uses the official GitHub CLI (gh) browser/device flow, so no
    token is ever written into the project. Safe to run again any time: it
    commits whatever has changed locally, creates the repo if it does not exist,
    and pushes so the GitHub copy matches your local folder exactly.

    Just double-click publish-to-github.cmd (which calls this script).
#>

# git and gh write normal progress to stderr. Under Windows PowerShell 5.1,
# $ErrorActionPreference='Stop' would turn that stderr into a fatal
# NativeCommandError (even on success), so we keep 'Continue' and check
# $LASTEXITCODE explicitly after every command that matters.
$ErrorActionPreference = 'Continue'
Set-Location -LiteralPath $PSScriptRoot

function Fail($msg) {
    Write-Host ""
    Write-Host "  $msg" -ForegroundColor Red
    Read-Host "`nPress Enter to close"
    exit 1
}
function Have($name) { [bool](Get-Command $name -ErrorAction SilentlyContinue) }
function Require-Success($msg) {
    if ($LASTEXITCODE -ne 0) { Fail $msg }
}
function Ensure-GitIdentity($user) {
    $gitName = (git config user.name 2>$null)
    if ($LASTEXITCODE -ne 0) { $gitName = "" }
    $gitEmail = (git config user.email 2>$null)
    if ($LASTEXITCODE -ne 0) { $gitEmail = "" }
    if ([string]::IsNullOrWhiteSpace($gitName)) {
        git config user.name $user | Out-Null
        Require-Success "Could not configure local git user.name."
        Write-Host "Configured local git user.name as $user." -ForegroundColor DarkGray
    }
    if ([string]::IsNullOrWhiteSpace($gitEmail)) {
        $fallbackEmail = "$user@users.noreply.github.com"
        git config user.email $fallbackEmail | Out-Null
        Require-Success "Could not configure local git user.email."
        Write-Host "Configured local git user.email as $fallbackEmail." -ForegroundColor DarkGray
    }
}
function Read-RepoVisibility() {
    while ($true) {
        $raw = Read-Host "Visibility - type 'private' or 'public' [public]"
        $value = $raw.Trim().ToLowerInvariant()
        if ([string]::IsNullOrWhiteSpace($value)) { return "public" }
        if ($value -eq "public" -or $value -eq "private") { return $value }
        Write-Host "Invalid visibility. Type 'public', 'private', or press Enter for public." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "== Publish HubShark to your GitHub account ==" -ForegroundColor Cyan
Write-Host ""

if (-not (Have git)) { Fail "Git is not installed. Get it from https://git-scm.com/download/win" }
if (-not (Have gh))  { Fail "GitHub CLI (gh) is not installed.`n  Install it with:  winget install --id GitHub.cli`n  Or download from: https://cli.github.com" }

# Make sure this folder is a git repository.
if (-not (Test-Path (Join-Path $PSScriptRoot '.git'))) {
    git init | Out-Null
    git branch -M main
}

# --- Log in to GitHub (opens your browser if you are not already signed in) ---
# Probe with an API call, NOT `gh auth status`: status returns nonzero when ANY
# stored account has an expired token, even if the active account is perfectly
# fine. `gh api user` reflects only the active account.
$user = (gh api user --jq .login 2>$null)
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($user)) {
    Write-Host "Opening GitHub login in your browser..." -ForegroundColor Yellow
    gh auth login
    if ($LASTEXITCODE -ne 0) { Fail "GitHub login did not complete." }
    $user = (gh api user --jq .login 2>$null)
    if ([string]::IsNullOrWhiteSpace($user)) { Fail "Could not read your GitHub username after login." }
}
$user = $user.Trim()
Write-Host "Logged in as: $user" -ForegroundColor Green
$ans = Read-Host "Publish to this account? Press Enter to accept, or type 'switch' to log into a different account"
if ($ans -eq 'switch') {
    gh auth login
    if ($LASTEXITCODE -ne 0) { Fail "GitHub login did not complete." }
    $user = (gh api user --jq .login 2>$null)
    if ([string]::IsNullOrWhiteSpace($user)) { Fail "Could not read your GitHub username after switching." }
    $user = $user.Trim()
    Write-Host "Now logged in as: $user" -ForegroundColor Green
}
gh auth setup-git 2>$null | Out-Null   # so an HTTPS push uses your gh login, no password prompt

# --- Repository name and visibility ---
$default = Split-Path $PSScriptRoot -Leaf
$repo = Read-Host "Repository name [$default]"
if ([string]::IsNullOrWhiteSpace($repo)) { $repo = $default }
$vis = Read-RepoVisibility
$slug = "$user/$repo"

# --- Commit any pending local changes so GitHub ends up identical to local ---
Ensure-GitIdentity $user
git add -A
Require-Success "Could not stage local changes."
git diff --cached --quiet
$diffExit = $LASTEXITCODE
if ($diffExit -eq 1) {
    $msg = Read-Host "Commit message [Update HubShark]"
    if ([string]::IsNullOrWhiteSpace($msg)) { $msg = "Update HubShark" }
    git commit -m $msg | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "Could not commit local changes." }
    Write-Host "Committed local changes." -ForegroundColor Green
} elseif ($diffExit -ne 0) {
    Fail "Could not inspect staged changes."
} else {
    Write-Host "Nothing new to commit; the working folder already matches the last commit." -ForegroundColor DarkGray
}

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -eq 'HEAD') { git branch -M main; $branch = 'main' }

# --- Create the repository on your account if it does not exist yet ---
gh repo view $slug 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) {
    Write-Host "Creating $vis repository $slug ..." -ForegroundColor Yellow
    gh repo create $slug "--$vis"
    if ($LASTEXITCODE -ne 0) { Fail "Could not create $slug on your account." }
}

# --- Point a dedicated remote at it and push the current branch ---
$url = "https://github.com/$slug.git"
if ((git remote) -contains 'mine') { git remote set-url mine $url } else { git remote add mine $url }
Write-Host "Pushing '$branch' to $slug ..." -ForegroundColor Yellow
git push -u mine $branch
if ($LASTEXITCODE -ne 0) { Fail "Push failed. Read the messages above for the reason." }

Write-Host ""
Write-Host "  Done. Your project is live at: https://github.com/$slug" -ForegroundColor Green
Read-Host "`nPress Enter to close"
