# App 7: HTTP và HTTPS

Ứng dụng cho nhập username và password **giả lập**, sau đó gửi đến backend local để so sánh HTTP, HTTPS tin cậy và HTTPS không tin cậy. Mỗi request hợp lệ mà backend nhận được tạo một dòng trong bảng `app7_transport_submissions`. Đây không phải chức năng đăng nhập thực tế.

**Chỉ nhập dữ liệu giả lập, không dùng tài khoản hoặc mật khẩu thật.** Theo mục đích trình diễn, password được lưu nguyên văn trong PostgreSQL. HTTP truyền nội dung không mã hóa; HTTPS bảo vệ nội dung khi truyền, sau đó TLS tại server giải mã trước khi backend lưu. Không có bước mã hóa password ở cấp ứng dụng hay băm mật khẩu.

## Chuẩn bị và chạy

1. Mở Docker Desktop. Trong thư mục `Backend`, chạy `.\Start.cmd` như các app trước. Nếu backend đang chạy phiên bản cũ, dừng bằng `Ctrl+C` trong terminal của chính nó rồi chạy lại để nạp API và tạo bảng App 7. Migration tạo bảng bổ sung trên volume hiện có; không xóa dữ liệu hoặc volume.
2. Lần đầu, backend dùng OpenSSL để tạo CA và hai chứng chỉ demo. `Start.cmd` tự tìm OpenSSL trong PATH, OpenSSL for Windows hoặc Git for Windows. Nếu chưa có OpenSSL, cài một trong các công cụ đó; các API HTTP cũ vẫn chạy nhưng hai cổng HTTPS của App 7 sẽ chưa hoạt động.
3. Mở thư mục App 7 trong Android Studio và chạy cấu hình `app` **debug** trên Android Emulator. Nếu `Start.cmd` thông báo đã cập nhật CA công khai, hãy build/cài lại app debug để chứng chỉ trong app khớp với backend.

Emulator truy cập host qua `10.0.2.2`. Các cổng đều chỉ nghe trên `127.0.0.1` của máy tính:

| Chế độ | Địa chỉ trên emulator | Ý nghĩa |
| --- | --- | --- |
| HTTP | `http://10.0.2.2:3000/api/transport-demo/submit` | Dữ liệu HTTP không được mã hóa trên đường truyền. |
| HTTPS | `https://10.0.2.2:3443/api/transport-demo/submit` | Chứng chỉ được CA demo ký và có SAN cho IP `10.0.2.2`. |
| Thử chứng chỉ | `https://10.0.2.2:3444/api/transport-demo/submit` | Chứng chỉ tự ký khác, không thuộc CA tin cậy của app. |

Cổng 3443/3444 chỉ phục vụ endpoint App 7. Nếu một cổng đã được chương trình khác sử dụng, backend sẽ báo lỗi HTTPS App 7 và vẫn giữ HTTP cho các demo cũ; hãy giải phóng cổng phù hợp rồi khởi động lại backend.

## Các bước demo

1. Nhập username và password giả lập, ví dụ `demo_student` và `DEMO_ONLY_123`. Cả hai trường bắt buộc; username tối đa 100 ký tự và password tối đa 128 ký tự. Password được che trên màn hình nhưng được lưu nguyên văn trong CSDL.
2. Để công tắc **Bật/Tắt bảo vệ HTTPS** tắt rồi nhấn **Gửi dữ liệu giả lập**. Backend lưu một bản ghi và card HTTP hiển thị mã bản ghi, trạng thái, mã phản hồi và thời gian. Công tắc chứng chỉ chỉ dùng được khi đã bật HTTPS.
3. Bật HTTPS và để **Dùng chứng chỉ không tin cậy** tắt. Nhấn gửi để kết nối cổng 3443; backend lưu thêm một bản ghi riêng và card HTTPS hiển thị kết quả.
4. Bật **Dùng chứng chỉ không tin cậy** rồi nhấn cùng nút gửi để thử cổng 3444. Kết quả mong đợi là Android từ chối xác thực trước khi app ghi nội dung; không có request HTTP hay bản ghi CSDL. Hai trường hợp HTTPS dùng chung một card. Nếu cổng/server không chạy hoặc bắt tay thất bại vì lý do chưa xác định, app thông báo chưa thể kết luận về chứng chỉ.
5. Nhấn từng card để xem chi tiết tối đa 30 lần gần nhất; lịch sử HTTPS ghi rõ loại chứng chỉ của từng lần. Ở chế độ HTTPS thành công, chi tiết hiển thị bộ mã TLS và chủ thể chứng chỉ. Thời gian là từ lúc tác vụ mạng bắt đầu đến khi nhận và xử lý response; có thể bao gồm bắt tay TLS, kết nối, xử lý backend và truyền dữ liệu. Các số đo chỉ mang tính tham khảo.

Số lần gửi của mỗi card tính cả lần lỗi và nằm trong bộ nhớ của phiên ứng dụng. Nếu Android bất ngờ chấp nhận chứng chỉ ở cổng thử nghiệm do cấu hình hoặc chứng chỉ thay đổi, app cảnh báo và tiếp tục gửi dữ liệu đã nhập theo cùng luồng; không có bước bỏ qua xác thực TLS. Nếu mất phản hồi sau khi đã bắt đầu ghi nội dung, app không thể khẳng định backend đã lưu hay chưa: kiểm tra CSDL trước khi gửi lại. Mỗi lần gửi thành công tạo dòng mới, kể cả khi dữ liệu giống lần trước.

Nút biểu tượng **Làm lại** xóa nội dung nhập và số liệu của phiên đang xem, giữ lựa chọn công tắc và không xóa các bản ghi trong PostgreSQL. Nút tạm thời không hoạt động khi request đang chạy.

## Xem dữ liệu trong PostgreSQL

Sau khi gửi thành công, mở PowerShell trong `Backend` và chạy:

```powershell
docker compose exec -T postgres psql -U mobile_demo -d mobile_optimization -c "SELECT id, username, password, created_at FROM app7_transport_submissions ORDER BY id DESC LIMIT 20;"
```

Mã `id` tương ứng với mã bản ghi trên app. Lệnh này hiển thị password nguyên văn, vì vậy chỉ dùng dữ liệu giả lập và tránh chia sẻ kết quả chứa thông tin thật. Backend không đưa username hoặc password vào response hay log request.

## Quan sát bằng Wireshark

1. Cài Wireshark cùng Npcap nếu máy chưa có. Trên Windows, trong danh sách interface chọn **Adapter for loopback traffic capture** rồi bắt đầu capture. Nếu không thấy gói tin, kiểm tra Npcap/quyền capture và lựa chọn interface; không cần cài CA demo vào hệ điều hành. Xem [hướng dẫn bắt loopback của Wireshark](https://wiki.wireshark.org/captureSetup/loopback).
2. Ở ô **display filter**, nhập `tcp.port == 3000 || tcp.port == 3443 || tcp.port == 3444`. Đây là bộ lọc hiển thị, không phải capture filter.
3. Nhập ví dụ `demo_student` / `DEMO_ONLY_123`, rồi gửi một lần ở HTTP và một lần ở HTTPS. Chọn một packet thuộc cổng `3000`, rồi **Analyze → Follow → TCP Stream**. Trong luồng HTTP, bạn có thể thấy dữ liệu đã nhập trong body JSON.
4. Chọn packet thuộc cổng `3443`, mở **Follow → TCP Stream** tương tự. Khi không cấu hình khóa giải mã TLS cho Wireshark, body JSON sẽ không hiển thị dưới dạng đọc được. Thông tin như địa chỉ, cổng và một số metadata kết nối vẫn có thể quan sát được.
5. Bật công tắc chứng chỉ không tin cậy và nhấn gửi để thử cổng `3444`: với cấu hình demo, quá trình bắt tay bị từ chối và app không ghi body. Lỗi bắt tay có thể xảy ra trước khi NestJS nhận được HTTP request, vì vậy backend không nhất thiết có một dòng request tương ứng.

Sử dụng dữ liệu giả lập và chỉ bắt lưu lượng của bài thực hành. Kết quả trên card cho biết trạng thái kết nối, không thay thế việc quan sát packet thực tế. Nếu chưa có Wireshark/Npcap, bài kiểm thử backend vẫn kiểm tra byte HTTP/TLS trên các socket cục bộ, nhưng đó không phải ảnh chụp Wireshark của emulator.

## Cấu hình chứng chỉ và giới hạn

- CA và khóa của server được tạo trong `Backend/.local-tls/app7`, thư mục đã được đưa vào `.gitignore`; **không chia sẻ hoặc commit khóa riêng**. Chỉ CA công khai được chép vào `app/src/debug/res/raw`.
- Bản debug chỉ cho phép HTTP tới `10.0.2.2` và dùng `debug-overrides` để tin CA demo. Bản release mặc định chặn HTTP và không chứa CA thử nghiệm. Không cài CA demo vào hệ điều hành, không dùng các khóa/cấu hình demo cho production.
- Client dùng xác thực chứng chỉ và tên máy chủ mặc định của Android; không cài `TrustManager` hoặc `HostnameVerifier` bỏ qua kiểm tra. Server cổng 3444 dùng chứng chỉ không tin cậy nhưng cùng SAN; đây là phép thử chuỗi tin cậy. Kiểm thử backend còn thử tên máy chủ không khớp.
- Chứng chỉ demo là cục bộ và có thời hạn. Nếu bộ file chứng chỉ bị thiếu một phần, script dừng và không tự ghi đè; hãy kiểm tra `Backend/.local-tls/app7`. Nếu tạo CA mới, phải cập nhật CA công khai và cài lại bản debug. Ứng dụng này chỉ cấu hình sẵn cho emulator, không tự hỗ trợ điện thoại thật.

Xem [Network Security Configuration của Android](https://developer.android.com/privacy-and-security/security-config) và [địa chỉ mạng emulator](https://developer.android.com/studio/run/emulator-networking-address).

## Kiểm thử

Trong `Backend`, sau khi CA đã được tạo: `npm run test:transport-demo`. Kiểm thử tạo một CSDL tạm riêng, kiểm tra migration, dữ liệu đã lưu, HTTP/HTTPS, chứng chỉ và byte trên socket, sau đó xóa CSDL kiểm thử do chính lệnh tạo. PostgreSQL cần đang chạy và tài khoản CSDL local cần quyền tạo CSDL.

Để kiểm thử Android, build `:app:assembleDebug :app:assembleDebugAndroidTest` trong thư mục App 7, kết nối emulator rồi chạy `npm run test:transport-android` trong `Backend`. Lệnh dùng CSDL tạm và các cổng 13070, 13473, 13474; kiểm tra dữ liệu Android thực sự được lưu, rồi dừng host và xóa CSDL kiểm thử của lệnh.
