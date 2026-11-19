# T3 — Tối ưu tải lại dữ liệu bằng Cache và Room

App Kotlin so sánh tải danh mục từ server với đọc dữ liệu đã lưu trong Room/SQLite. App có thêm demo **đồng bộ sách mới theo phiên bản** khi ứng dụng đang hiển thị hoặc sau khi mở lại.

## Khởi động

1. Mở Docker Desktop. Trong `Backend`, dừng backend cũ nếu đang chạy, sau đó chạy:

   ```powershell
   .\Start.cmd
   ```

   Lần đầu sau cập nhật, script cài thêm thư viện WebSocket nếu cần và tạo hai bảng đồng bộ mới. Các bảng sách và dữ liệu có sẵn được giữ lại; không xóa Docker volume. Lần cài dependency có thể cần Internet.
2. Mở thư mục App 3 trong Android Studio và chạy cấu hình `app` trên Android Emulator API 26 trở lên. Địa chỉ backend là `http://10.0.2.2:3000` (HTTP chỉ cho phép trong debug).
3. Bật **Bật/Tắt tối ưu Cache** và mở danh mục một lần để có cache ban đầu. App tải tối đa 1.000 sách đầu; nếu đã có cache 30 sách từ bản cũ, app vẫn hiển thị cache rồi bổ sung danh mục gốc khi kết nối lại, giữ các sách đã đồng bộ.

## Thêm sách và kiểm tra đồng bộ

Trong một terminal khác ở thư mục `Backend`, chạy:

```powershell
.\Add-App3-Book.cmd
```

Mỗi lần chạy thêm một sách thật vào PostgreSQL, ghi nhật ký thay đổi và phát tín hiệu WebSocket sau khi transaction thành công. Có thể cung cấp tùy chọn, ví dụ:

```powershell
.\Add-App3-Book.cmd -Title "Sach demo" -Author "Tac gia" -Price 120000
```

Khi tối ưu đang bật và app hiển thị, kết nối WebSocket nhận tín hiệu, app kiểm tra phiên bản rồi chỉ tải sách mới. Số lượng Room và danh mục tự tăng; sách mới có ID lớn nên nằm ở cuối danh sách. Nếu chạy script khi app đóng, mở lại app: app tự kiểm tra và lấy bù các sách còn thiếu. Tắt tối ưu sẽ dừng WebSocket và đồng bộ. Khi kết nối thật bị mất, app giữ cache và thử kết nối lại khi đang hiển thị.

Để kiểm tra khi mất mạng, ngắt kết nối trên emulator. Nếu Wi-Fi vẫn đang kết nối, chỉ tắt mạng di động có thể chưa làm thiết bị mất mạng; hãy kiểm tra cả Wi-Fi. Khi kết nối trở lại, app thử đồng bộ lại nếu tối ưu đang bật và app đang hiển thị.

Lần đầu phải có cache: mở danh mục khi có mạng. Đồng bộ chỉ hoạt động trong khi app hiển thị; ứng dụng không đăng ký WorkManager/FCM để đồng bộ khi đóng. Một request đã bắt đầu trước khi rời màn hình có thể kết thúc sau đó.

## Phạm vi dữ liệu

- Chế độ **Tắt** tải tối đa 1.000 sách đầu và không đọc/ghi Room.
- Chế độ **Bật** giữ tối đa 1.000 sách gốc cộng những sách được thêm bằng `Add-App3-Book.cmd` hoặc API demo tương ứng. Dữ liệu được thêm qua script vẫn xuất hiện sau khi xóa cache và tải lại vì app lấy lại nhật ký thay đổi. Cách này có phạm vi khác trang đầu thuần túy của chế độ Tắt sau khi thêm sách.
- Phiên bản theo dõi **các sách thêm qua script/API demo**; thay đổi hay xóa trực tiếp trong PostgreSQL hoặc qua đường khác không được ghi vào nhật ký đồng bộ này. Đây là demo thêm bản ghi, chưa phải đồng bộ tổng quát thêm/sửa/xóa.
- WebSocket chỉ báo có thay đổi; dữ liệu thật được lấy bằng API HTTP theo mốc đã lưu. Nếu tín hiệu bị mất, việc kiểm tra khi mở app hoặc kết nối lại giúp lấy bù. Mốc và dữ liệu từng trang được lưu cùng transaction; việc xóa cache làm vô hiệu các cập nhật cũ đang chạy.
- Cache có thể hiển thị ngay khi mất mạng. Mốc **60 giây** chỉ cho biết tuổi dữ liệu lưu lần gần nhất, không xóa cache và không quyết định có kiểm tra phiên bản hay không. Mỗi lần app mở lại khi bật tối ưu và có cache, app sẽ thực hiện request kiểm tra nhỏ, kể cả khi cache còn mới.

## Số đo

Card ON/OFF cho biết số lần mở, số request **tải danh mục**, số byte body nhận được và thời gian tới lần hiển thị nội dung đầu tiên. Những số này không bao gồm request đồng bộ tự động.

Khung **Dữ liệu lưu trong Room** hiển thị số sách và thời gian cập nhật. Các số đo chỉ giữ trong tiến trình; nút refresh cạnh tiêu đề so sánh xóa số đo, không xóa cache.

Backend ghi log **CSDL** cho các request danh mục và đồng bộ của App 3. Thời gian này đo tại backend quanh lệnh truy vấn hoặc transaction, bao gồm thời gian chờ kết nối và giao tiếp PostgreSQL; nó không phải toàn bộ thời gian HTTP hay thời gian hiển thị trên máy.

Nhấn **Xóa cache Room** để xóa bản sao và mốc đồng bộ; nút này không xóa sách trên backend. [Hướng dẫn Room](https://developer.android.com/training/data-storage/room) giải thích cách Room sử dụng SQLite trên thiết bị.

## Kiểm tra

Chạy `.\gradlew.bat :app:connectedDebugAndroidTest` với emulator để kiểm tra Room, migration và rollback. Backend có `npm run test:app3-sync`; kiểm thử này chỉ cho phép ghi vào PostgreSQL cô lập có tên `app3_sync_test` và yêu cầu đặt `MOBILE_DEMO_DB_PORT` tới container thử nghiệm.
