# pull-objectbox-db.ps1
# Pull ObjectBox database from Android device for viewing in ObjectBox Admin

$ErrorActionPreference = "Stop"

Write-Host "=== ObjectBox Database Puller ===" -ForegroundColor Cyan
Write-Host ""

# Configuration
$packageName = "com.ambientai"
$localPath = "C:\Users\rasche_j\Downloads\objectbox"
$adbPath = "C:\Users\rasche_j\AppData\Local\Android\Sdk\platform-tools\adb.exe"

# Check PowerShell version
$psVersion = $PSVersionTable.PSVersion.Major
Write-Host "PowerShell Version: $psVersion" -ForegroundColor Yellow

# Verify adb exists
Write-Host "Checking for adb..." -ForegroundColor Yellow
if (-not (Test-Path $adbPath)) {
    Write-Host "ERROR: adb.exe not found at $adbPath" -ForegroundColor Red
    exit 1
}
Write-Host "✓ adb found" -ForegroundColor Green

# Check device connection
Write-Host "Checking device connection..." -ForegroundColor Yellow
$devices = & $adbPath devices | Out-String
if ($devices -notmatch "\tdevice") {
    Write-Host "ERROR: No device connected. Run 'adb devices' to verify." -ForegroundColor Red
    exit 1
}
Write-Host "✓ Device connected" -ForegroundColor Green

# Clean and create local directory
Write-Host "Preparing local directory..." -ForegroundColor Yellow
if (Test-Path $localPath) {
    if ((Get-Item $localPath) -is [System.IO.FileInfo]) {
        Write-Host "Removing existing file blocking directory..." -ForegroundColor Yellow
        Remove-Item $localPath -Force
    } else {
        Write-Host "Cleaning existing directory..." -ForegroundColor Yellow
        Remove-Item "$localPath\*" -Force -ErrorAction SilentlyContinue
    }
}
New-Item -ItemType Directory -Path $localPath -Force | Out-Null
Write-Host "✓ Directory ready: $localPath" -ForegroundColor Green

# Verify files exist on device
Write-Host "Checking files on device..." -ForegroundColor Yellow
$fileList = & $adbPath shell "run-as $packageName ls files/objectbox/objectbox/" 2>&1
if ($fileList -match "data.mdb") {
    Write-Host "✓ data.mdb found on device" -ForegroundColor Green
} else {
    Write-Host "ERROR: data.mdb not found. File list:" -ForegroundColor Red
    Write-Host $fileList
    exit 1
}

# Pull files based on PowerShell version
Write-Host ""
Write-Host "Pulling database files..." -ForegroundColor Yellow

if ($psVersion -ge 6) {
    Write-Host "Using PowerShell 6+ binary mode (batch wrapper)" -ForegroundColor Cyan

    # Create temporary batch file for data.mdb
    $batchData = @"
@echo off
"$adbPath" exec-out run-as $packageName cat files/objectbox/objectbox/data.mdb > "$localPath\data.mdb"
"@
    $batchDataPath = "$env:TEMP\pull_data.bat"
    [System.IO.File]::WriteAllText($batchDataPath, $batchData)

    Write-Host "  Pulling data.mdb..."
    cmd /c $batchDataPath

    # Create temporary batch file for lock.mdb
    $batchLock = @"
@echo off
"$adbPath" exec-out run-as $packageName cat files/objectbox/objectbox/lock.mdb > "$localPath\lock.mdb" 2>nul
"@
    $batchLockPath = "$env:TEMP\pull_lock.bat"
    [System.IO.File]::WriteAllText($batchLockPath, $batchLock)

    Write-Host "  Pulling lock.mdb..."
    cmd /c $batchLockPath

    # Cleanup
    Remove-Item $batchDataPath -Force -ErrorAction SilentlyContinue
    Remove-Item $batchLockPath -Force -ErrorAction SilentlyContinue
} else {
    Write-Host "Using PowerShell 5.x binary mode (batch file wrapper)" -ForegroundColor Cyan

    # Create temporary batch file for data.mdb
    $batchData = @"
@echo off
"$adbPath" exec-out run-as $packageName cat files/objectbox/objectbox/data.mdb > "$localPath\data.mdb"
"@
    $batchDataPath = "$env:TEMP\pull_data.bat"
    [System.IO.File]::WriteAllText($batchDataPath, $batchData)

    Write-Host "  Pulling data.mdb..."
    cmd /c $batchDataPath

    # Create temporary batch file for lock.mdb
    $batchLock = @"
@echo off
"$adbPath" exec-out run-as $packageName cat files/objectbox/objectbox/lock.mdb > "$localPath\lock.mdb" 2>nul
"@
    $batchLockPath = "$env:TEMP\pull_lock.bat"
    [System.IO.File]::WriteAllText($batchLockPath, $batchLock)

    Write-Host "  Pulling lock.mdb..."
    cmd /c $batchLockPath

    # Cleanup
    Remove-Item $batchDataPath -Force -ErrorAction SilentlyContinue
    Remove-Item $batchLockPath -Force -ErrorAction SilentlyContinue
}

# Verify files were created
Write-Host ""
Write-Host "Verifying downloaded files..." -ForegroundColor Yellow
$dataMdb = Get-Item "$localPath\data.mdb" -ErrorAction SilentlyContinue
if ($dataMdb -and $dataMdb.Length -gt 0) {
    Write-Host "✓ data.mdb: $($dataMdb.Length) bytes" -ForegroundColor Green
} else {
    Write-Host "ERROR: data.mdb is missing or empty" -ForegroundColor Red
    exit 1
}

$lockMdb = Get-Item "$localPath\lock.mdb" -ErrorAction SilentlyContinue
if ($lockMdb) {
    Write-Host "✓ lock.mdb: $($lockMdb.Length) bytes" -ForegroundColor Green
} else {
    Write-Host "⚠ lock.mdb not found (may be optional)" -ForegroundColor Yellow
}

# Success
Write-Host ""
Write-Host "=== SUCCESS ===" -ForegroundColor Green
Write-Host "Database files saved to: $localPath"
Write-Host ""

# Check if Docker is running
Write-Host "Checking Docker..." -ForegroundColor Yellow
try {
    $dockerVersion = docker version 2>&1
    if ($LASTEXITCODE -ne 0) {
        Write-Host "⚠ Docker Desktop not running. Please start Docker Desktop first." -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Once Docker is running, execute:" -ForegroundColor Cyan
        Write-Host "docker run --rm -it --volume ${localPath}:/db --publish 8081:8081 objectboxio/admin:latest" -ForegroundColor White
        exit 0
    }
    Write-Host "✓ Docker is running" -ForegroundColor Green
} catch {
    Write-Host "⚠ Docker not found. Install Docker Desktop first." -ForegroundColor Yellow
    exit 0
}

# Start ObjectBox Admin
Write-Host ""
Write-Host "Starting ObjectBox Admin..." -ForegroundColor Yellow
Write-Host "Press Ctrl+C to stop the server when done viewing" -ForegroundColor Cyan
Write-Host ""

# Start Docker container in background job
$job = Start-Job -ScriptBlock {
    param($path)
    docker run --rm --volume "${path}:/db" --publish 8081:8081 objectboxio/admin:latest
} -ArgumentList $localPath

# Wait for server to start
Start-Sleep -Seconds 3

# Check if job is still running (server started successfully)
if ($job.State -eq "Running") {
    Write-Host "✓ ObjectBox Admin started" -ForegroundColor Green
    Write-Host "Opening browser..." -ForegroundColor Yellow
    Start-Process "http://localhost:8081"

    Write-Host ""
    Write-Host "=== ObjectBox Admin is running ===" -ForegroundColor Green
    Write-Host "View your database at: http://localhost:8081" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "Press any key to stop the server and exit..." -ForegroundColor Yellow
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")

    # Stop the Docker container
    Write-Host "Stopping ObjectBox Admin..." -ForegroundColor Yellow
    Stop-Job $job
    Remove-Job $job
    docker stop $(docker ps -q --filter ancestor=objectboxio/admin:latest) 2>$null
    Write-Host "✓ Server stopped" -ForegroundColor Green
} else {
    Write-Host "ERROR: ObjectBox Admin failed to start. Database file may be corrupted." -ForegroundColor Red
    Write-Host ""
    Write-Host "Try pulling the database again:" -ForegroundColor Yellow
    Write-Host "Remove-Item '$localPath\*' -Force" -ForegroundColor White
    Write-Host "Then re-run this script" -ForegroundColor White
    Receive-Job $job
    Remove-Job $job
    exit 1
}