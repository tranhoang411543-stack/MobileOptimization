# T4.1 — Tối ưu ảnh bằng Coil (cách hiện đại, so sánh với T4)

Phiên bản hiện đại của [T4](<../T4 - Tối ưu ảnh bằng cách tải đúng kích thước hiển thị/README.md>). Ứng dụng dùng **cùng backend** (`/api/image-demo/*`), **cùng 40 sản phẩm với 8 ảnh JPEG 2000 × 1500 px**, **cùng kích thước hiển thị 96dp** và **cùng các chỉ số đo** (Ảnh đã hiện, Request ảnh, Dữ liệu tải, Giải mã TB, Hiển thị TB, Bitmap gần nhất, Đang giữ, Giữ cao nhất) như T4.

T4 đã tối ưu bằng `BitmapFactory` với `inSampleSize`, nên khác biệt chính nằm ở **cách tối ưu và cache**:

| Thành phần | T4 (BitmapFactory thủ công) | T4.1 (Coil 3) |
|---|---|---|
| Tối ưu kích thước ảnh | Tính `inSampleSize` thủ công, giải mã với kích thước phù hợp | Coil tự động lấy mẫu theo kích thước Composable |
| Cache bộ nhớ | Không có (mỗi lần tải lại đều giải mã lại) | Memory cache tích hợp (lần 2 nhanh hơn) |
| Cache đĩa | Không có | Disk cache tích hợp (sau restart vẫn có) |
| Giải mã ngoài luồng giao diện | `Executors.newFixedThreadPool(2)` | Coroutine Dispatcher của Coil |
| Giao diện | View XML + `ListView` | Jetpack Compose + `LazyColumn` + `AsyncImage` |
| Hủy request khi cuộn | Thủ công qua token và `Future.cancel` | Coil tự hủy khi composable bị dispose |
| Chế độ tắt tối ưu | Giải mã ảnh đầy đủ bằng `BitmapFactory` | Giống T4: giải mã thủ công, không dùng Coil |

Cơ chế đo metrics: T4.1 vẫn **đo HTTP bytes tải qua ImageApi**, nhưng ở chế độ bật, việc giải mã do Coil thực hiện nên không có thời gian giải mã chi tiết như T4. Chế độ tắt vẫn giải mã thủ công để so sánh công bằng.

## Cách chạy

1. Mở Docker Desktop, chạy `Backend\Start.cmd` (giống T4).
2. Mở **thư mục này** bằng Android Studio, chạy cấu hình `app` (debug) trên emulator API 26 trở lên.
3. Bật/tắt tối ưu, cuộn danh sách và xem card metrics để so sánh.

Kiểm thử (không cần emulator): `gradlew testDebugUnitTest`. Bộ test kiểm tra logic `ImageMetrics` (thêm record, giới hạn 100 record gần nhất, format bytes).

## So sánh với T4

**Điểm mạnh của T4.1:**
- Mã ngắn gọn hơn nhờ Compose và Coil (không cần quản lý `Executor`, token, `Handler.post`, `ViewTreeObserver`).
- Coil tự động tối ưu kích thước ảnh theo Composable, không cần tính `inSampleSize` thủ công.
- Memory/disk cache giúp lần tải lại nhanh hơn (T4 không có cache).
- Coil tự hủy request khi composable bị dispose, không cần quản lý lifecycle thủ công.

**Điểm cần lưu ý:**
- T4.1 dùng Compose, Coil và OkHttp nên APK lớn hơn T4: bản debug 10,0 MiB so với 0,87 MiB; bản release dù đã bật R8 vẫn 1,14 MiB so với 654 KiB của T4 (không R8).
- Coil có memory/disk cache, nên so sánh công bằng cần đo **lần tải đầu tiên** (chưa cache) vs T4. Lần tải sau do cache nên nhanh hơn, nhưng đây là tính năng của Coil, không phải so sánh 1:1 với T4.
- Chế độ tắt tối ưu của T4.1 vẫn giải mã thủ công bằng `BitmapFactory` (không dùng Coil) để có baseline giống T4.

## Số liệu đo trên emulator

**Chưa đo trên emulator.** Cần chạy thực tế trên Pixel 8 API 36 với cùng kịch bản: xóa dữ liệu app → mở chế độ tắt → cuộn 10 ảnh → bật tối ưu → cuộn 10 ảnh lần 1 (lần đầu, chưa cache) → cuộn lại 10 ảnh lần 2 (đã cache).

## Số liệu đo trên máy phát triển

| Chỉ số | T4 | T4.1 |
|---|---|---|
| Dòng mã Kotlin (không tính dòng trống, chú thích, test) | 599 | 552 |
| Dòng XML layout | 84 | 0 |
| APK debug | 0,87 MiB | 10,0 MiB |
| APK release (T4 không R8, T4.1 có R8) | 654 KiB | 1,14 MiB |

Dù bật R8, bản release của T4.1 vẫn lớn hơn T4 khoảng 1,7 lần vì phải kèm Compose, Coil và OkHttp. Đây là chi phí cần nêu khi đánh giá.

## Lưu ý

- **Bản release không gọi được backend local**, vì cấu hình cho phép HTTP tới `10.0.2.2` chỉ nằm trong `src/debug`. Để kiểm tra R8, nhóm đã build tạm một bản release có cấu hình này và chạy thử.
- T4.1 dùng Coil 3.0.4 (phiên bản ổn định đã phát hành từ tháng 10/2024). Coil 3 hỗ trợ Compose Multiplatform và cải tiến cache.
- Chế độ bật tối ưu dùng Coil `AsyncImage` với cache mặc định. Chế độ tắt vẫn giải mã thủ công bằng `BitmapFactory` để có baseline công bằng.
- Metrics ở chế độ bật không đo được thời gian giải mã chi tiết vì Coil xử lý nội bộ. Có thể mở rộng bằng cách dùng Coil Interceptor để đo chính xác hơn.

## Kết luận

T4.1 minh họa cách hiện đại để tải ảnh trên Android: dùng thư viện chuyên dụng (Coil) thay vì tự triển khai. Coil giúp:
- Giảm boilerplate code (không cần quản lý `Executor`, token, lifecycle thủ công).
- Tối ưu tự động (resize theo view, decode ngoài luồng chính).
- Cache miễn phí (memory + disk).
- Tích hợp tốt với Compose.

Tuy nhiên, T4 vẫn có giá trị để hiểu nguyên lý bên trong (`inSampleSize`, quản lý bitmap, threading). T4.1 là cách viết production-ready.
