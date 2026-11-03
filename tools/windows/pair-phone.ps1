# Mobile Vault: pair a phone with the backup server in one step (Task 09).
# Copyright (C) 2026  github.com/spirtosan, GPL-3.0-or-later
#
# Double-click "Pair phone.cmd". This script:
#   1. reads the SSH settings from %USERPROFILE%\mobilevault-secrets\pair-phone.json (never in the repo),
#   2. asks for a device name,
#   3. runs `mvault-admin pair <name>` on the server over SSH and gets the pairing QR back as text
#      (base64 PNG made by qrencode on the server, streamed, nothing is stored there),
#   4. shows the QR full screen with a 10-minute countdown and the paste text as a fallback.
# The QR image is kept in memory only: no PNG file is written on the PC either.
# Windows PowerShell 5.1 + the built-in OpenSSH client. ASCII only (PS 5.1 reads BOM-less files as ANSI).

$ErrorActionPreference = 'Stop'
$configPath = Join-Path $env:USERPROFILE 'mobilevault-secrets\pair-phone.json'
$NameRe = '^[A-Za-z0-9][A-Za-z0-9._-]{0,47}$'       # same rule as mvault-admin (common.NAME_RE)
$TtlSeconds = 600                                    # pair_ttl_seconds on the server

function Stop-WithMessage([string]$text) {
    Write-Host ""
    Write-Host $text -ForegroundColor Yellow
    Write-Host ""
    [void](Read-Host 'Press Enter to close')
    exit 1
}

# -- 1. settings ------------------------------------------------------------
if (-not (Test-Path $configPath)) {
    Stop-WithMessage (@"
No settings file found:
  $configPath

Create it with the SSH login of your server (see tools\windows\pair-phone.example.json):
  {
    "ssh": "<user>@<server address>",
    "port": 22,
    "identity": "",
    "sudo": false
  }
"identity" is an optional private key file (empty = your normal SSH key or a password prompt).
"sudo": true runs "sudo -n mvault-admin" for a non-root user (needs passwordless sudo for it).
No password is ever stored by this script.
"@)
}
try { $cfg = Get-Content -Raw -Path $configPath | ConvertFrom-Json } catch { Stop-WithMessage "The settings file is not valid JSON: $configPath" }
if (-not $cfg.ssh) { Stop-WithMessage "The settings file has no ""ssh"" entry (like ""<user>@<server address>"")." }
$port = if ($cfg.port) { [int]$cfg.port } else { 22 }

# Prefer the Windows OpenSSH client; fall back to whatever "ssh" is on PATH.
$sshExe = Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe'
if (-not (Test-Path $sshExe)) {
    $cmd = Get-Command ssh -ErrorAction SilentlyContinue
    if (-not $cmd) { Stop-WithMessage 'No SSH client found. Install "OpenSSH Client" in Windows Settings > Optional features.' }
    $sshExe = $cmd.Source
}

# -- 2. device name ---------------------------------------------------------
Write-Host 'Mobile Vault: connect a phone to the backup server' -ForegroundColor Cyan
Write-Host ''
$default = 'phone-' + (Get-Date -Format 'yyyyMMdd')
while ($true) {
    $name = Read-Host "Name for this phone [$default]"
    if ([string]::IsNullOrWhiteSpace($name)) { $name = $default }
    $name = $name.Trim()
    if ($name -match $NameRe) { break }
    Write-Host 'Use letters, digits, "." "_" "-" (up to 48 characters, starting with a letter or digit).' -ForegroundColor Yellow
}

# -- 3. pair on the server --------------------------------------------------
# The script goes to "bash -s" on stdin; the name is passed as an argument (already validated above).
$mvault = if ($cfg.sudo) { 'sudo -n mvault-admin' } else { 'mvault-admin' }
$remote = @"
set -e
command -v qrencode >/dev/null || { echo 'qrencode is not installed on the server (apt install qrencode)' >&2; exit 4; }
out=`$($mvault pair "`$1")
code=`$(printf '%s\n' "`$out" | sed -n 's/^Code: //p')
payload=`$(printf '%s\n' "`$out" | sed -n 's/^Payload: //p')
[ -n "`$payload" ] || { printf '%s\n' "`$out" >&2; exit 3; }
printf 'CODE:%s\n' "`$code"
printf 'PAYLOAD:%s\n' "`$payload"
printf 'PNG:'
printf '%s' "`$payload" | qrencode -t PNG -s 10 -m 4 -o - | base64 -w0
printf '\n'
"@ -replace "`r", ''

$sshArgs = @('-p', "$port", '-o', 'ConnectTimeout=15')
if ($cfg.identity) { $sshArgs += @('-i', "$($cfg.identity)") }
$sshArgs += @("$($cfg.ssh)", 'bash', '-s', '--', $name)

Write-Host ''
Write-Host "Connecting to $($cfg.ssh) ..."
$psi = New-Object System.Diagnostics.ProcessStartInfo
$psi.FileName = $sshExe
$psi.Arguments = ($sshArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
$psi.UseShellExecute = $false
$psi.RedirectStandardInput = $true
$psi.RedirectStandardOutput = $true          # stderr and password prompts stay on this console
# .NET Framework writes the console input encoding's BOM into the child's stdin; bash would choke on it.
$oldInEnc = [Console]::InputEncoding
try { [Console]::InputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }
$p = [System.Diagnostics.Process]::Start($psi)
try { [Console]::InputEncoding = $oldInEnc } catch { }
$bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($remote)
$p.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
$p.StandardInput.Close()
$out = $p.StandardOutput.ReadToEnd()
$p.WaitForExit()
if ($p.ExitCode -ne 0) { Stop-WithMessage "Pairing on the server failed (exit code $($p.ExitCode)). See the messages above." }

$code = ''; $payload = ''; $png = ''
foreach ($line in ($out -split "`n")) {
    $l = $line.TrimEnd("`r")
    if ($l.StartsWith('CODE:')) { $code = $l.Substring(5) }
    elseif ($l.StartsWith('PAYLOAD:')) { $payload = $l.Substring(8) }
    elseif ($l.StartsWith('PNG:')) { $png = $l.Substring(4) }
}
if (-not $payload -or -not $png) { Stop-WithMessage 'The server did not return a pairing code.' }
$started = Get-Date

# -- 4. full-screen QR ------------------------------------------------------
Add-Type -AssemblyName System.Windows.Forms, System.Drawing
$ms = New-Object System.IO.MemoryStream(, [Convert]::FromBase64String($png))
$img = [System.Drawing.Image]::FromStream($ms)

$form = New-Object System.Windows.Forms.Form
$form.Text = "Mobile Vault pairing: $name"
$form.FormBorderStyle = 'None'; $form.WindowState = 'Maximized'; $form.TopMost = $true
$form.BackColor = [System.Drawing.Color]::White; $form.KeyPreview = $true

$layout = New-Object System.Windows.Forms.TableLayoutPanel
$layout.Dock = 'Fill'; $layout.ColumnCount = 1; $layout.RowCount = 5; $layout.BackColor = [System.Drawing.Color]::White
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute', 70)))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Percent', 100)))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute', 50)))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute', 60)))
[void]$layout.RowStyles.Add((New-Object System.Windows.Forms.RowStyle('Absolute', 40)))

function New-Label([string]$text, [float]$size, [bool]$bold) {
    $l = New-Object System.Windows.Forms.Label
    $l.Text = $text; $l.Dock = 'Fill'; $l.TextAlign = 'MiddleCenter'
    $style = if ($bold) { [System.Drawing.FontStyle]::Bold } else { [System.Drawing.FontStyle]::Regular }
    $l.Font = New-Object System.Drawing.Font('Segoe UI', $size, $style)
    $l.ForeColor = [System.Drawing.Color]::Black
    return $l
}

$title = New-Label "Scan with Mobile Vault on the phone: Settings > Backup > Connect > Scan QR code      Device: $name" 16 $true
$pb = New-Object System.Windows.Forms.PictureBox
$pb.Dock = 'Fill'; $pb.SizeMode = 'Zoom'; $pb.Image = $img
$countdown = New-Label '' 16 $false
$paste = New-Object System.Windows.Forms.TextBox
$paste.ReadOnly = $true; $paste.Dock = 'Fill'; $paste.BorderStyle = 'None'; $paste.TextAlign = 'Center'
$paste.BackColor = [System.Drawing.Color]::White; $paste.Font = New-Object System.Drawing.Font('Consolas', 10)
$paste.Text = "Code: $code     Paste text (fallback): $payload"
$hint = New-Label 'Esc or click the QR to close' 10 $false
$hint.ForeColor = [System.Drawing.Color]::Gray

$layout.Controls.Add($title, 0, 0); $layout.Controls.Add($pb, 0, 1); $layout.Controls.Add($countdown, 0, 2)
$layout.Controls.Add($paste, 0, 3); $layout.Controls.Add($hint, 0, 4)
$form.Controls.Add($layout)

$script:expired = $false
$timer = New-Object System.Windows.Forms.Timer
$timer.Interval = 1000
$update = {
    $left = [int]($TtlSeconds - ((Get-Date) - $started).TotalSeconds)
    if ($left -le 0) { $script:expired = $true; $timer.Stop(); $form.Close(); return }
    $countdown.Text = 'Valid for {0}:{1:D2} more (single use)' -f [int][Math]::Floor($left / 60), ($left % 60)
}
$timer.Add_Tick($update)
& $update
$form.Add_KeyDown({ if ($_.KeyCode -eq 'Escape') { $form.Close() } })
$pb.Add_Click({ $form.Close() })
$form.Add_Shown({ $form.Activate(); $timer.Start() })
[void]$form.ShowDialog()

# The code exists only in memory: drop it.
$timer.Dispose(); $pb.Image = $null; $img.Dispose(); $ms.Dispose(); $form.Dispose()
$png = $null; $payload = $null; $code = $null; $out = $null

Write-Host ''
if ($script:expired) { Write-Host 'The pairing code expired (10 minutes). Run "Pair phone" again if the phone did not connect.' -ForegroundColor Yellow }
Write-Host 'Scanned? On the phone: Settings > Backup > Test connection.' -ForegroundColor Green
Write-Host "Replacing or lost an old phone? On the server: mvault-admin revoke <old name>   (this one is '$name')"
Write-Host ''
[void](Read-Host 'Press Enter to close')
