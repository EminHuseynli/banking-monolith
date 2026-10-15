# Top-left pane of the manual run: container state, monolith health via Nginx,
# ERROR/WARN line counts in the application logs, RabbitMQ queue depth (C2). Refreshes every 3 s; Ctrl+C to stop.
#   .\scripts\health-loop.ps1
Set-Location (Join-Path $PSScriptRoot "..")
$u = "http://localhost/actuator/health"
while ($true) {
  Clear-Host
  Get-Date -Format "HH:mm:ss"
  docker compose ps --format "table {{.Service}}`t{{.State}}`t{{.Status}}"
  try { $h = Invoke-RestMethod -Uri $u -TimeoutSec 3; "`nmonolith health (via nginx): $($h.status)" }
  catch { "`nmonolith health: DOWN ($($_.Exception.Message))" }
  $logs = docker compose logs --no-color monolith account-service transaction-service api-gateway 2>$null
  "ERROR lines: " + ($logs | Select-String -Pattern '\bERROR\b').Count + "   WARN lines: " + ($logs | Select-String -Pattern '\bWARN\b').Count
  $q = docker compose exec -T rabbitmq rabbitmqctl -q list_queues name messages 2>$null | Select-String notification.queue
  if ($q) { "RabbitMQ " + ($q.Line -replace "\s+", " = ") + " (unconsumed)" }
  Start-Sleep 3
}
