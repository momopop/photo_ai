# PhotoAI 本地开发启动脚本（Windows PowerShell）
Write-Host "=== PhotoAI 本地开发环境启动 ===" -ForegroundColor Cyan

# 检查 Python 环境
Write-Host "`n[1/4] 检查 Python 环境..." -ForegroundColor Yellow
$pythonVersion = python --version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: 未找到 Python，请安装 Python 3.11+" -ForegroundColor Red
    exit 1
}
Write-Host "  Python: $pythonVersion" -ForegroundColor Green

# 检查 Node.js 环境
Write-Host "`n[2/4] 检查 Node.js 环境..." -ForegroundColor Yellow
$nodeVersion = node --version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "ERROR: 未找到 Node.js，请安装 Node.js 18+" -ForegroundColor Red
    exit 1
}
Write-Host "  Node.js: $nodeVersion" -ForegroundColor Green

# 启动 Python AI 服务
Write-Host "`n[3/4] 启动 Python AI 服务 (端口 8000)..." -ForegroundColor Yellow
$aiServicePath = Join-Path $PSScriptRoot "..\ai-service"
Push-Location $aiServicePath

if (-not (Test-Path "venv")) {
    Write-Host "  创建虚拟环境..." -ForegroundColor Gray
    python -m venv venv
    .\venv\Scripts\pip install -r requirements.txt -q
}

Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$aiServicePath'; .\venv\Scripts\python -m uvicorn main:app --reload --port 8000" -WindowStyle Normal
Pop-Location
Write-Host "  AI 服务已启动: http://localhost:8000" -ForegroundColor Green

Start-Sleep -Seconds 3

# 启动 Node.js 后端
Write-Host "`n[4/4] 启动 Node.js 后端 (端口 3000)..." -ForegroundColor Yellow
$backendPath = Join-Path $PSScriptRoot "..\backend"
Push-Location $backendPath

if (-not (Test-Path "node_modules")) {
    Write-Host "  安装 Node 依赖..." -ForegroundColor Gray
    npm install
}

# 复制 .env 文件
if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
}

Start-Process powershell -ArgumentList "-NoExit", "-Command", "cd '$backendPath'; npm run dev" -WindowStyle Normal
Pop-Location
Write-Host "  后端已启动: http://localhost:3000" -ForegroundColor Green

Write-Host "`n=== 所有服务已启动 ===" -ForegroundColor Cyan
Write-Host "AI 服务文档: http://localhost:8000/docs" -ForegroundColor White
Write-Host "后端接口: http://localhost:3000" -ForegroundColor White
Write-Host "健康检查: http://localhost:3000/api/compose/health" -ForegroundColor White
Write-Host "`n前端开发 (UniApp H5 模式):" -ForegroundColor Yellow
Write-Host "  cd frontend && npm install && npm run dev:h5" -ForegroundColor White
Write-Host "  然后访问 http://localhost:8080" -ForegroundColor White
