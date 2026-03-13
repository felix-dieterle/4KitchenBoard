# update_backend_windows.ps1
#
# Pulls the latest backend files from GitHub and copies them to the local
# Apache web-root directory (e.g. XAMPP htdocs).
#
# REQUIREMENTS
#   - Git for Windows  https://git-scm.com/download/win
#   - PowerShell 5.1+ (included in Windows 10/11)
#
# USAGE
#   Right-click the file → "Run with PowerShell"
#   – or from a terminal –
#   powershell -ExecutionPolicy Bypass -File "C:\path\to\update_backend_windows.ps1"
#
# SCHEDULE (example: every 6 hours via Windows Task Scheduler)
#   Run the following command once in an elevated command prompt to register
#   the task.  Adjust the -File path to wherever you saved this script.
#
#   schtasks /create ^
#     /tn "4KitchenBoard Backend Update" ^
#     /tr "powershell -ExecutionPolicy Bypass -File \"C:\scripts\update_backend_windows.ps1\"" ^
#     /sc hourly /mo 6 ^
#     /ru SYSTEM /f
#
# ── CONFIGURATION ──────────────────────────────────────────────────────────────

# Full path to the directory where Apache/XAMPP serves files.
# This should be the folder where api.php will be reachable as
#   http://localhost/apps/kitchenboard/api.php
$ApacheTargetPath = "F:\CascadeProjects\mama-razzi\public\apps\kitchenboard"

# Local directory where the repository clone is kept.
# The script creates this directory and the initial clone automatically.
$LocalRepoPath    = "F:\temp\kitchenboard-repo"

# GitHub repository (owner/name) and branch to track.
$GitHubRepo       = "felix-dieterle/4KitchenBoard"
$GitHubBranch     = "main"

# ──────────────────────────────────────────────────────────────────────────────

$ErrorActionPreference = "Stop"

# Log file sits next to this script.
$LogFile = Join-Path $PSScriptRoot "update_backend.log"

function Write-Log {
    param([string]$Message)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $line = "[$timestamp] $Message"
    Write-Host $line
    Add-Content -Path $LogFile -Value $line
}

Write-Log "=== 4KitchenBoard backend update started ==="

# ── Step 1: Clone or update the local repository ─────────────────────────────

if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    Write-Log "ERROR: git is not installed or not in PATH."
    Write-Log "       Install Git for Windows from https://git-scm.com/download/win"
    exit 1
}

try {
    if (Test-Path (Join-Path $LocalRepoPath ".git")) {
        Write-Log "Updating existing clone at '$LocalRepoPath' ..."
        $output = git -C $LocalRepoPath fetch --quiet origin $GitHubBranch 2>&1
        $output | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "git fetch failed with exit code $LASTEXITCODE"
        }
        $output = git -C $LocalRepoPath reset --hard "origin/$GitHubBranch" 2>&1
        $output | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "git reset failed with exit code $LASTEXITCODE"
        }
    } else {
        # If directory exists but isn't a git repo, remove it first
        if (Test-Path $LocalRepoPath) {
            Write-Log "Removing existing non-git directory at '$LocalRepoPath' ..."
            Remove-Item -Path $LocalRepoPath -Recurse -Force
        }
        Write-Log "Cloning https://github.com/$GitHubRepo into '$LocalRepoPath' ..."
        $output = git clone --branch $GitHubBranch --depth 1 `
            "https://github.com/$GitHubRepo.git" $LocalRepoPath 2>&1
        $output | ForEach-Object { Write-Log $_ }
        if ($LASTEXITCODE -ne 0) {
            throw "git clone failed with exit code $LASTEXITCODE"
        }
    }
} catch {
    Write-Log "ERROR during git step: $_"
    exit 1
}

# ── Step 2: Copy backend files to the Apache target directory ─────────────────

$BackendSource = Join-Path $LocalRepoPath "backend"

if (-not (Test-Path $BackendSource)) {
    Write-Log "ERROR: backend folder not found at '$BackendSource'."
    exit 1
}

try {
    if (-not (Test-Path $ApacheTargetPath)) {
        Write-Log "Creating target directory '$ApacheTargetPath' ..."
        New-Item -ItemType Directory -Path $ApacheTargetPath -Force | Out-Null
    }

    # Copy every file from backend/ EXCEPT config.php.
    # config.php contains local database credentials and the API token -
    # it must never be overwritten by a remote update.
    $files = Get-ChildItem -Path $BackendSource -File
    foreach ($file in $files) {
        if ($file.Name -eq "config.php") {
            Write-Log "Skipping config.php (local credentials - never overwritten by update)"
            continue
        }
        $dest = Join-Path $ApacheTargetPath $file.Name
        Copy-Item -Path $file.FullName -Destination $dest -Force
        Write-Log "Copied: $($file.Name) -> $dest"
    }

    Write-Log "Done. Backend files are up to date in '$ApacheTargetPath'."
} catch {
    Write-Log "ERROR during copy step: $_"
    exit 1
}