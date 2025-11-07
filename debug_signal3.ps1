param(
	[string]$Session = '',
	[string]$Token = '',
	[string]$PayloadValue = 'parallel-test-3'
)

Set-StrictMode -Version Latest

function Write-Header($s) { Write-Host "`n==== $s ==== `n" }

Push-Location (Split-Path -Path $MyInvocation.MyCommand.Path)

# If session/token not provided, create one via POST /create
if ([string]::IsNullOrWhiteSpace($Session) -or [string]::IsNullOrWhiteSpace($Token)) {
	Write-Header "Creating new session"
	$createOut = curl.exe -s -X POST "http://localhost:8080/aggregate/webrtc/create" -H "Content-Type: application/json" -d "{}"
	if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($createOut)) {
		Write-Error "Failed to create session: exit=$LASTEXITCODE output=$createOut"
		Pop-Location
		exit 1
	}
	$json = $createOut | Out-String | ConvertFrom-Json
	$Session = $json.sessionId
	$Token = $json.token
	Write-Host "Created session=$Session token(truncated)=$($Token.Substring(0,40))..."
}

$streamUrl = "http://localhost:8080/aggregate/webrtc/$Session/stream?token=$Token"
$signalUrl = "http://localhost:8080/aggregate/webrtc/$Session/signal"

Write-Header "Prepare payload"
$payloadObj = @{ token = $Token; payload = $PayloadValue }
$payloadObj | ConvertTo-Json -Compress | Out-File -Encoding UTF8 payload.json
Get-Content payload.json

Write-Header "Start SSE subscriber (background)"
# Use cmd.exe to allow shell redirection; capture stdout and stderr separately
$arg = '/c', "curl.exe -i -N `"$streamUrl`" > stream_output.txt 2> stream_error.txt"
$p = Start-Process -FilePath 'cmd.exe' -ArgumentList $arg -NoNewWindow -PassThru
Start-Sleep -Milliseconds 500

function Wait-ForFileNonEmpty($path, $timeoutSeconds) {
	$sw = [Diagnostics.Stopwatch]::StartNew()
	while ($sw.Elapsed.TotalSeconds -lt $timeoutSeconds) {
		if (Test-Path $path) {
			$fi = Get-Item $path
			if ($fi.Length -gt 0) { return $true }
		}
		Start-Sleep -Milliseconds 200
	}
	return $false
}

if (-not (Wait-ForFileNonEmpty 'stream_output.txt' 10)) {
	Write-Warning "stream_output.txt not created or empty after 10s. Check stream_error.txt for curl errors."
}

Write-Header "POST the payload"
& curl.exe -s -i -X POST $signalUrl -H 'Content-Type: application/json' --data-binary '@payload.json' -o post_response.txt
Write-Host "POST RESPONSE:"; Get-Content post_response.txt -Raw

Start-Sleep -Seconds 1

Write-Header "SSE output (stdout)"
if (Test-Path 'stream_output.txt') { Get-Content 'stream_output.txt' -Raw } else { Write-Output 'stream_output.txt not found' }

Write-Header "SSE stderr (curl)"
if (Test-Path 'stream_error.txt') { Get-Content 'stream_error.txt' -Raw } else { Write-Output 'stream_error.txt not found' }

Write-Header "Aggregator recent logs (if docker-compose available)"
try {
	$dc = & docker-compose --version 2>$null
	if ($LASTEXITCODE -eq 0) {
		& docker-compose logs aggregator --no-color --tail=200 | Out-File -Encoding UTF8 aggregator_logs.txt
		Get-Content aggregator_logs.txt -Raw
	} else {
		Write-Output "docker-compose not available in PATH"
	}
} catch {
	Write-Output "docker-compose logs failed: $_"
}

Pop-Location
