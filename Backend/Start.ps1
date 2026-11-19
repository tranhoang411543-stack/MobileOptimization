$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

function Assert-Success([string] $message) {
    if ($LASTEXITCODE -ne 0) { throw $message }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Không tìm thấy Docker. Hãy cài và mở Docker Desktop.'
}
if (-not (Get-Command node -ErrorAction SilentlyContinue) -or -not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw 'Không tìm thấy Node.js/npm. Hãy cài Node.js 20.19 trở lên.'
}

Write-Host 'Đang kiểm tra Docker Desktop...' -ForegroundColor Cyan
docker info --format '{{.ServerVersion}}' | Out-Null
Assert-Success 'Không kết nối được Docker Desktop. Hãy mở Docker Desktop và thử lại.'

Write-Host 'Đang khởi động PostgreSQL và chờ dữ liệu mẫu sẵn sàng...' -ForegroundColor Cyan
docker compose up -d --wait --wait-timeout 120
Assert-Success 'Không khởi động được PostgreSQL. Hãy kiểm tra Docker Desktop và cổng 5549.'

if (-not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules\ts-node\register\index.js')) -or
    -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules\ws\package.json')) -or
    -not (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'node_modules\@types\ws\package.json'))) {
    Write-Host 'Đang cài các thư viện backend lần đầu...' -ForegroundColor Cyan
    npm.cmd ci
    Assert-Success 'Không cài được thư viện. Hãy kiểm tra kết nối mạng rồi chạy lại lệnh.'
}

Write-Host 'Đang kiểm tra chứng chỉ thử nghiệm của App 7...' -ForegroundColor Cyan
node scripts/setup-app7-tls.cjs
if ($LASTEXITCODE -ne 0) {
    Write-Warning 'Chưa chuẩn bị được HTTPS App 7; backend HTTP và các app khác vẫn tiếp tục chạy.'
}

Write-Host ''
Write-Host 'Backend: http://127.0.0.1:3000/api/health' -ForegroundColor Green
Write-Host 'Android Emulator kết nối qua: http://10.0.2.2:3000' -ForegroundColor Green
Write-Host 'Giữ cửa sổ này mở; nhấn Ctrl+C để dừng backend.'
npm.cmd run start
Assert-Success 'Backend đã dừng do lỗi. Hãy xem thông báo phía trên.'
