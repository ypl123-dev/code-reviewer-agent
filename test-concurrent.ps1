$diff = Get-Content -Raw ".\test-sample.sql-injection.diff"
$url = "http://localhost:9090/code-reviewer/api/chat/review"
$concurrency = 12
$results = [System.Collections.Concurrent.ConcurrentDictionary[int,object]]::new()

$runspacePool = [runspacefactory]::CreateRunspacePool(1, $concurrency)
$runspacePool.Open()
$jobs = @()

for ($i = 1; $i -le $concurrency; $i++) {
    $ps = [powershell]::Create()
    $ps.RunspacePool = $runspacePool
    $null = $ps.AddScript({
        param($idx, $diff, $url, $results)
        $sid = "conc-$idx-$(Get-Date -Format 'HHmmss')"
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        try {
            $resp = Invoke-WebRequest -Uri "$url`?sessionId=$sid" -Method Post -Body $diff -ContentType "text/plain" -UseBasicParsing -TimeoutSec 90
            $sw.Stop()
            $results[$idx] = [PSCustomObject]@{ Idx=$idx; Status=$resp.StatusCode; TimeMs=$sw.ElapsedMilliseconds; Len=$resp.Content.Length }
        } catch {
            $sw.Stop()
            $code = 0
            if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode }
            $results[$idx] = [PSCustomObject]@{ Idx=$idx; Status=$code; TimeMs=$sw.ElapsedMilliseconds; Len=0; Err=$_.Exception.Message }
        }
    })
    $null = $ps.AddArgument($i).AddArgument($diff).AddArgument($url).AddArgument($results)
    $jobs += [PSCustomObject]@{ PS=$ps; Handle=$ps.BeginInvoke() }
}

Write-Host "已发起 $concurrency 个并发请求，等待完成(最长90s)..."
$jobs | ForEach-Object { $_.PS.EndInvoke($_.Handle) | Out-Null; $_.PS.Dispose() }
$runspacePool.Close(); $runspacePool.Dispose()

$ok = $results.Values | Where-Object { $_.Status -eq 200 }
$limited = $results.Values | Where-Object { $_.Status -eq 429 }
$other = $results.Values | Where-Object { $_.Status -ne 200 -and $_.Status -ne 429 }

Write-Host "===== 并发压测结果 (并发=$concurrency) ====="
Write-Host "成功(200): $($ok.Count)  限流(429): $($limited.Count)  其他: $($other.Count)"
if ($ok.Count -gt 0) {
    $avgOk = ($ok | Measure-Object TimeMs -Average).Average
    $maxOk = ($ok | Measure-Object TimeMs -Maximum).Maximum
    $minOk = ($ok | Measure-Object TimeMs -Minimum).Minimum
    Write-Host "成功请求耗时 - 平均: ${avgOk}ms  最小: ${minOk}ms  最大: ${maxOk}ms"
}
$results.Values | Sort-Object Idx | Format-Table Idx,Status,TimeMs,Len,Err -AutoSize
