$diff = Get-Content -Raw "d:\ai编程\trae\08\test-sample.sql-injection.diff"
$sessionId = "test-single-$(Get-Date -Format 'yyyyMMddHHmmss')"
$url = "http://localhost:9090/code-reviewer/api/chat/review?sessionId=$sessionId"

$sw = [System.Diagnostics.Stopwatch]::StartNew()
$resp = Invoke-WebRequest -Uri $url -Method Post -Body $diff -ContentType "text/plain" -UseBasicParsing
$sw.Stop()

$content = $resp.Content
$content | Out-File "d:\ai编程\trae\08\test-result-1.txt" -Encoding UTF8

Write-Host "===== 单次审查结果 ====="
Write-Host "HTTP状态: $($resp.StatusCode)"
Write-Host "耗时: $($sw.ElapsedMilliseconds) ms"
Write-Host "响应字节数: $($resp.RawContentLength)"
Write-Host "响应字符数: $($content.Length)"
$tokenEst = [math]::Round($content.Length / 3.5)
Write-Host "Token估算(输出): ~$tokenEst"
Write-Host ""
Write-Host "===== 审查内容片段 ====="
Write-Host $content.Substring(0, [math]::Min(2000, $content.Length))
