# AI 服务环境初始化脚本
Write-Host "=== PhotoAI - AI 服务环境初始化 ===" -ForegroundColor Cyan

$aiServicePath = Join-Path $PSScriptRoot "..\ai-service"
Push-Location $aiServicePath

Write-Host "`n[1/3] 创建 Python 虚拟环境..." -ForegroundColor Yellow
python -m venv venv
Write-Host "  完成" -ForegroundColor Green

Write-Host "`n[2/3] 安装依赖包（可能需要几分钟）..." -ForegroundColor Yellow
.\venv\Scripts\pip install --upgrade pip -q
.\venv\Scripts\pip install -r requirements.txt
Write-Host "  完成" -ForegroundColor Green

Write-Host "`n[3/3] 预下载 YOLOv8 模型..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force -Path "models" | Out-Null
.\venv\Scripts\python -c "from ultralytics import YOLO; m = YOLO('yolov8n.pt'); import shutil; shutil.copy(m.ckpt_path if hasattr(m,'ckpt_path') else 'yolov8n.pt', 'models/yolov8n.pt') if __import__('pathlib').Path('yolov8n.pt').exists() else None; print('模型下载完成')"
Write-Host "  完成" -ForegroundColor Green

Pop-Location

Write-Host "`n=== 环境初始化完成 ===" -ForegroundColor Cyan
Write-Host "运行以下命令启动服务:" -ForegroundColor White
Write-Host "  .\scripts\start-dev.ps1" -ForegroundColor Yellow
