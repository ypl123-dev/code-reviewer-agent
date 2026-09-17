$diff = Get-Content -Raw ".\test-sample.sql-injection.diff"
$url = "http://localhost:9090/code-reviewer/api/chat/review"
$results = @()

for ($i = 1; $i -le 3; $i++) {
    $sid = "bench-$i-$(Get-Date -Format 'HHmmss')"
    $body = @{ sessionId = $sid } 
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-WebRequest -Uri "$url`?sessionId=$sid" -Method Post -Body $diff -ContentType "text/plain" -UseBasicParsing
        $sw.Stop()
        $ms = $sw.ElapsedMilliseconds
        $len = $resp.Content.Length
        $results += [PSCustomObject]@{ Run=$i; TimeMs=$ms; ContentLen=$len; Status="OK" }
        Write-Host "Run $i : ${ms}ms, len=$len"
    } catch {
        $sw.Stop()
        $results += [PSCustomObject]@{ Run=$i; TimeMs=$sw.ElapsedMilliseconds; ContentLen=0; Status="ERR:$($_.Exception.Message)" }
        Write-Host "Run $i : ERR $($_.Exception.Message)"
    }
    Start-Sleep -Seconds 2
}

$avg = ($results | Measure-Object TimeMs -Average).Average
$min = ($results | Measure-Object TimeMs -Minimum).Minimum
$max = ($results | Measure-Object TimeMs -Maximum).Maximum
Write-Host "===== 汇总 ====="
Write-Host "平均: ${avg}ms  最小: ${min}ms  最大: ${max}ms"
$results | Format-Table
