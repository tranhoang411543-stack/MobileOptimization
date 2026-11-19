param(
    [string]$Title = '',
    [string]$Author = '',
    [int]$Price = 120000,
    [string]$BaseUrl = 'http://127.0.0.1:3000'
)
$ErrorActionPreference = 'Stop'
$payload = @{ priceVnd = $Price }
if ($Title.Trim()) { $payload.title = $Title.Trim() }
if ($Author.Trim()) { $payload.author = $Author.Trim() }
try {
    $json = $payload | ConvertTo-Json -Compress
    $result = Invoke-RestMethod -Method Post -Uri "$($BaseUrl.TrimEnd('/'))/api/app3-sync/demo-books" `
        -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json))
    Write-Host "Đã thêm sách #$($result.book.id): $($result.book.title)" -ForegroundColor Green
    Write-Host "Phiên bản đồng bộ: $($result.version). App 3 sẽ nhận tín hiệu khi đang mở, bật tối ưu và WebSocket đã kết nối."
} catch {
    Write-Error "Không thể thêm sách. Hãy kiểm tra backend đã chạy bản mới: $($_.Exception.Message)"
    exit 1
}
