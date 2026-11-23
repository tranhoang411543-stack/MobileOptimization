# T1.1 — Tối ưu tìm kiếm bằng Flow debounce (cách hiện đại)

Phiên bản hiện đại của [T1](../T1%20-%20T%E1%BB%91i%20%C6%B0u%20t%C3%ACm%20ki%E1%BA%BFm%20b%E1%BA%B1ng%20Debounce/README.md). Ứng dụng dùng **cùng API, cùng dữ liệu, cùng thời gian chờ 400 ms và cùng các chỉ số đo** như T1, chỉ thay cách triển khai:

| Thành phần | T1 (truyền thống) | T1.1 (hiện đại) |
|---|---|---|
| Giao diện | XML layout + `findViewById` | Jetpack Compose (Material 3) |
| Giữ trạng thái | Activity + `onRetainNonConfigurationInstance` | `ViewModel` + `StateFlow` |
| Debounce | `Handler.postDelayed` + `removeCallbacks` | `Flow.debounce(400)` + `collectLatest` |
| Gọi HTTP | `HttpURLConnection` + `Executor` (4 luồng) | Retrofit `suspend` + OkHttp |
| Đọc JSON | `org.json` thủ công | kotlinx.serialization |
| Request cũ | Vẫn chạy đến hết, kết quả bị bỏ qua | **Bị hủy thật** ở tầng OkHttp |
| Xoay màn hình | Request đang chạy bị đánh dấu "gián đoạn", ô tìm kiếm bị xóa | Request tiếp tục, giữ nguyên từ khóa |
| Giữ kết nối | Lớp `ConnectionKeeper` (≈80 dòng, `ScheduledExecutor`) | Vòng lặp coroutine trong `repeatOnLifecycle(STARTED)` |

## Hai chế độ

- **Tắt tối ưu:** mỗi lần nội dung thay đổi sẽ khởi chạy một coroutine gọi HTTP, giống T1.
- **Bật tối ưu:** từ khóa đi qua `debounce(400)`. Khi debounce phát ra từ khóa mới, `collectLatest` hủy coroutine cũ, và Retrofit/OkHttp hủy luôn request đang chạy.

Thẻ số liệu có thêm dòng **Đã hủy**: số request đã bắt đầu nhưng bị hủy vì có từ khóa mới. Request bị hủy không được tính vào *Tổng HTTP*.

## Cách chạy

1. Mở Docker Desktop, chạy `Backend\Start.cmd` và giữ cửa sổ đó mở.
2. Mở **thư mục này** bằng Android Studio, chờ Gradle Sync.
3. Chạy cấu hình `app` (debug) trên Android Emulator (API 26 trở lên).

Kiểm thử đơn vị (không cần emulator hay backend): `gradlew testDebugUnitTest`. Bộ test dùng thời gian ảo để kiểm tra:

- tắt tối ưu, gõ “android” → 7 request;
- bật tối ưu, gõ nhanh → 1 request;
- bật tối ưu, có từ khóa mới khi request cũ đang chạy → request cũ ở trạng thái *Đã hủy*;
- nhấn Làm lại → xóa số đo và hủy request đang chạy.

## Trình diễn so sánh với T1

1. Cài cả T1 và T1.1 lên cùng emulator.
2. Ở mỗi app, nhấn **Làm lại**, gõ `Android` với cùng nhịp ở chế độ tắt rồi bật tối ưu.
3. Ghi lại *Yêu cầu, Đã hủy, Kết quả, CSDL, Tổng HTTP, Tổng CSDL*.
4. Thử thêm hai tình huống mà T1.1 khác T1:
   - gõ `kotlin`, chờ khoảng 0,5 giây rồi gõ thêm `x` → xem request bị hủy trong *Xem chi tiết*;
   - xoay màn hình khi đang tìm → T1 báo “gián đoạn”, T1.1 vẫn hiển thị kết quả.

## Số liệu đo trên máy phát triển (không cần chạy app)

| Chỉ số | T1 | T1.1 |
|---|---|---|
| APK debug | 0,87 MiB | 9,8 MiB |
| APK release (T1 không R8, T1.1 có R8) | 651 KiB | 1,29 MiB |
| Dòng mã Kotlin (không tính dòng trống, chú thích, test) | 543 | 523 (đã gồm toàn bộ giao diện) |

T1 còn khoảng 180 dòng XML layout. Như vậy T1.1 ít mã hơn, nhưng APK lớn hơn vì phải kèm Compose, Retrofit và OkHttp. Đây là chi phí cần nêu khi đánh giá.

## Lưu ý về số đo

- *Kết quả (HTTP)* ở T1.1 tính đến khi Retrofit trả về đối tượng, nên gồm cả bước đọc JSON. Với 20 sách, bước này rất nhỏ so với thời gian mạng.
- OkHttp có thể tự thử lại ở tầng vận chuyển. Các lần thử lại này không được tính riêng, giống T1.
- Bản release dùng R8. Hãy chạy thử bản release trên emulator trước khi đưa số liệu vào báo cáo, để chắc R8 không làm hỏng Retrofit hoặc kotlinx.serialization.
