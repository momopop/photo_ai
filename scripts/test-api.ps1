# PhotoAI API 快速测试脚本
# 需要 curl 可用（Windows 10 1803+ 内置）

$BACKEND = "http://localhost:3000"
$AI_SERVICE = "http://localhost:8000"

Write-Host "=== PhotoAI API 测试 ===" -ForegroundColor Cyan

# 1. 后端健康检查
Write-Host "`n[TEST 1] 后端健康检查" -ForegroundColor Yellow
$r1 = Invoke-WebRequest -Uri "$BACKEND/health" -ErrorAction SilentlyContinue
if ($r1.StatusCode -eq 200) {
    Write-Host "  ✓ 后端正常: $($r1.Content)" -ForegroundColor Green
} else {
    Write-Host "  ✗ 后端异常" -ForegroundColor Red
}

# 2. AI 服务健康检查
Write-Host "`n[TEST 2] AI 服务健康检查" -ForegroundColor Yellow
$r2 = Invoke-WebRequest -Uri "$AI_SERVICE/health" -ErrorAction SilentlyContinue
if ($r2.StatusCode -eq 200) {
    Write-Host "  ✓ AI 服务正常: $($r2.Content)" -ForegroundColor Green
} else {
    Write-Host "  ✗ AI 服务异常（确认端口 8000 已启动）" -ForegroundColor Red
}

# 3. 联动状态检查
Write-Host "`n[TEST 3] 后端->AI 联动检查" -ForegroundColor Yellow
$r3 = Invoke-WebRequest -Uri "$BACKEND/api/compose/health" -ErrorAction SilentlyContinue
if ($r3.StatusCode -eq 200) {
    Write-Host "  ✓ 联动正常: $($r3.Content)" -ForegroundColor Green
} else {
    Write-Host "  ✗ 联动异常" -ForegroundColor Red
}

# 4. 图片分析测试（需要测试图片）
Write-Host "`n[TEST 4] 图片分析接口" -ForegroundColor Yellow
$testImage = Join-Path $PSScriptRoot "test.jpg"
if (Test-Path $testImage) {
    $result = curl.exe -s -X POST "$BACKEND/api/analyze" -F "image=@$testImage"
    $json = $result | ConvertFrom-Json
    if ($json.success) {
        $score = $json.data.composition.total_score
        $type = $json.data.composition.composition_type
        Write-Host "  ✓ 分析成功! 构图评分: $score，类型: $type" -ForegroundColor Green
    } else {
        Write-Host "  ✗ 分析失败: $result" -ForegroundColor Red
    }
} else {
    Write-Host "  跳过（将测试图片放置为 scripts\test.jpg 即可测试）" -ForegroundColor Gray
}

Write-Host "`n=== 测试完成 ===" -ForegroundColor Cyan
