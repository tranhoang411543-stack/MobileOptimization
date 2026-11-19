# T4 — Tối ưu ảnh bằng cách tải đúng kích thước hiển thị

Ứng dụng Android/Kotlin hiển thị 40 sản phẩm giả lập từ backend local. Có 8 ảnh JPEG sản phẩm khác nhau, mỗi ảnh **2000 × 1500 px**; các ảnh được dùng lại cho nhiều mục để có đủ danh sách cuộn. Cả hai chế độ dùng cùng URL ảnh và ô hiển thị **96 × 96dp**:

- **Tắt tối ưu:** tải JPEG rồi giải mã bitmap `ARGB_8888` ở kích thước gốc.
- **Bật tối ưu:** đọc kích thước ảnh trước, dùng `BitmapFactory.Options.inSampleSize` để giải mã nhỏ hơn, rồi thu về gần kích thước cần cho `centerCrop`. Ảnh quá nhỏ không bị phóng lớn trong bước giải mã.

Đây là cách tự triển khai bằng `BitmapFactory` để thấy rõ phép đo, không sử dụng Coil hoặc Glide. Cả hai chế độ đều sử dụng `ListView` tái sử dụng hàng, giải mã ngoài luồng giao diện và giới hạn hai tác vụ ảnh chạy đồng thời. Các request không còn gắn với hàng sẽ bị hủy hoặc bỏ qua khi hoàn tất; ảnh cũ không được gán vào hàng đã tái sử dụng.

**Lưu ý:** hai chế độ đều tải **cùng file JPEG gốc** từ backend; tối ưu ở đây làm giảm kích thước bitmap giải mã trong bộ nhớ, không làm giảm dung lượng JPEG tải qua mạng. App không có cache ảnh trong RAM/đĩa, tắt HTTP cache và dùng `Accept-Encoding: identity` để việc so sánh tập trung vào giải mã. Trong sản phẩm thực tế có thể kết hợp server/CDN trả ảnh phù hợp và thư viện tải ảnh có cache.

## Cách chạy

1. Mở Docker Desktop. Nếu backend đang chạy phiên bản cũ, nhấn `Ctrl+C` rồi chạy lại trong thư mục `Backend`:

   ```powershell
   .\Start.cmd
   ```

2. Mở thư mục **T4 - Tối ưu ảnh bằng cách tải đúng kích thước hiển thị** trong Android Studio, đợi Gradle Sync.
3. Chọn Android Emulator API 26 trở lên và nhấn **Run** với cấu hình `app` ở chế độ debug. HTTP tới `10.0.2.2` chỉ được cho phép trong bản debug.

Ảnh được lưu trong `Backend/assets/image-demo` và được backend phục vụ local; không cần Internet để tải ảnh lúc chạy. Nguồn và tác giả trong [ATTRIBUTION.md](../Backend/assets/image-demo/ATTRIBUTION.md). Tên, giá sản phẩm là dữ liệu giả lập.

## Cách trình bày

1. Để **Tắt tối ưu**, chờ ảnh xuất hiện. Cuộn qua một số sản phẩm, sau đó quay về đầu để xem card hoặc nhấn card xem chi tiết.
2. Bật tối ưu và lặp lại với cùng vị trí, số ảnh và thao tác cuộn. App tải lại ảnh từ backend và giải mã theo kích thước đích; bộ số đo của chế độ còn lại được giữ lại.
3. Nhấn từng card để xem kích thước ảnh gốc, bitmap sau giải mã, bộ nhớ pixel, `inSampleSize`, HTTP và thời gian của từng request. Lịch sử chi tiết giữ 100 request gần nhất; số tổng vẫn được cộng trong phiên.
4. Nút refresh đặt lại hai bộ số đo và tải lại ảnh của chế độ hiện tại. Nếu lỗi tải sản phẩm, nhấn thông báo cuối danh sách để thử lại; nếu ảnh lỗi, nhấn ô ảnh để thử lại.

Để quan sát bộ nhớ toàn diện và độ mượt khi cuộn, dùng **Android Studio Memory Profiler** và công cụ frame rendering/jank của Android Studio. So sánh nhiều lần với cùng emulator, vị trí, nhịp cuộn và thời điểm đo; việc log/đo cũng có chi phí.

## Ý nghĩa số đo

- **Ảnh đã hiện:** số lượt ảnh thực sự được gắn vào ô đang nhìn thấy và đã đến khung hình đầu tiên. Cùng một sản phẩm có thể được tính lại nếu cuộn ra rồi quay về.
- **Req ảnh / Đã nhận:** số request ảnh bắt đầu và tổng byte body JPEG/response đọc xong. Không bao gồm request lấy danh sách sản phẩm, HTTP headers hoặc TCP/IP. Request bị hủy khi cuộn có thể không hoàn tất; thao tác cuộn khác nhau có thể tạo số request khác nhau.
- **Giải mã TB:** trung bình thời gian đọc thông số ảnh và giải mã/thu nhỏ trong các lần giải mã thành công, không bao gồm HTTP. **Hiển thị TB:** trung bình thời gian xấp xỉ từ khi hàng yêu cầu ảnh, gồm chờ hàng đợi, HTTP và giải mã, đến sau khi ảnh đi vào khung hình đầu tiên; không bảo đảm thời điểm GPU/màn hình vật lý hoàn tất hiển thị. Hai chỉ số có tập mẫu khác nhau vì ảnh giải mã xong có thể đã bị cuộn ra ngoài.
- **Bitmap gần nhất:** kích thước bitmap và `allocationByteCount` thực tế của ảnh giải mã gần nhất, bao gồm cả ảnh hoàn tất nhưng không còn hiện trên màn hình. Ví dụ `2000 × 1500 × 4` byte xấp xỉ **11,4 MiB** pixel ở `ARGB_8888`; kích thước ô 96dp quy đổi sang pixel tùy mật độ màn hình.
- **Đang giữ / Giữ cao nhất:** tổng byte pixel của bitmap hiện được gắn vào các ô do app theo dõi và giá trị lớn nhất trong phiên. Không bao gồm bitmap tạm, bộ nhớ chưa được thu gom, ảnh nằm trong GPU hoặc toàn bộ RAM tiến trình; không xem đây là mức đỉnh RAM tổng.
- Khi chuyển chế độ, app gỡ bitmap của chế độ cũ khỏi các ô nên **Đang giữ** của card cũ có thể về 0. Giá trị **Giữ cao nhất** của phiên cũ vẫn được giữ để đối chiếu.

Việc đo thời gian và bộ nhớ có thể dao động do kết nối nguội/đã dùng, cache hệ điều hành, GC, hình ảnh khác nhau và nhịp cuộn. Không kết luận rằng từng request hoặc từng lần cuộn luôn nhanh hơn chỉ từ một phép đo.

Để kiểm tra bộ giải mã trên emulator: `.\gradlew.bat :app:connectedDebugAndroidTest`. Bài kiểm tra xác nhận ảnh gốc và ảnh tối ưu có kích thước/bộ nhớ khác nhau, màu sắc còn phù hợp và ảnh không hợp lệ bị từ chối.
