# Bottom-left pane of the manual run: client helpers. Load once with
#   . .\scripts\client.ps1
# Call <METHOD> <path> <user> [body]  prints the HTTP status (green 2xx, yellow 4xx, red 5xx)
#                                     and the response; the parsed body is kept in $Last.
# Login <user>                        logs <user>@exp.test in and keeps its token.
$global:B = "http://localhost"; $global:T = @{}
function global:Call($Method, $Path, $User, $Body) {
  $h = @{}; if ($User) { $h.Authorization = "Bearer " + $T[$User] }
  $json = if ($Body) { $Body | ConvertTo-Json -Compress } else { $null }
  try {
    $r = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri ($B + $Path) -Headers $h -ContentType "application/json" -Body $json
    $code = [int]$r.StatusCode; $content = $r.Content
  } catch {
    if (-not $_.Exception.Response) { Write-Host "$Method $Path -> NO RESPONSE" -ForegroundColor Red; return }
    $code = [int]$_.Exception.Response.StatusCode; $content = $_.ErrorDetails.Message
  }
  $color = if ($code -lt 300) {"Green"} elseif ($code -lt 500) {"Yellow"} else {"Red"}
  Write-Host "`n$Method $Path  as $User  -> $code" -ForegroundColor $color
  $global:Last = if ($content) { $content | ConvertFrom-Json } else { $null }
  if ($Last -and $Last[0].accountNumber) { $Last | Format-Table id,accountNumber,accountType,balance,status,userId -AutoSize | Out-String | Write-Host }
  elseif ($Last -and $Last[0].transactionType) { $Last | Format-Table id,transactionType,amount,sourceAccountId,targetAccountId,description -AutoSize | Out-String | Write-Host }
  elseif ($Last) { $Last | Format-List | Out-String | Write-Host }
}
function global:Login($User) { Call POST /api/auth/login $null @{email="$User@exp.test"; password="secret123"}; $T[$User] = $Last.token }
