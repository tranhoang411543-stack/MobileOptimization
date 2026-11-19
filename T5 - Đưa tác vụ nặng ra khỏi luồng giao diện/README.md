# App 5 — Đưa tác vụ nặng ra khỏi luồng giao diện

Ứng dụng Kotlin dùng cùng backend local của các bài trước. Mục tiêu là so sánh khả năng **phản hồi giao diện** trong lúc đọc và xử lý cùng một file CSV, không khẳng định luồng nền luôn xử lý xong nhanh hơn.

## Chạy demo

1. Mở Docker Desktop, sau đó khởi động backend bằng lệnh đã có ở thư mục `Backend`:

   ```powershell
   .\Start.ps1
   ```

   Nếu backend đã chạy từ trước khi bổ sung App 5, khởi động lại backend để nạp API mới. Không cần xóa hay tạo lại PostgreSQL.
2. Mở thư mục App 5 này bằng Android Studio, đồng bộ Gradle, chạy cấu hình `app` trên Android Emulator. Bản debug kết nối backend qua `http://10.0.2.2:3000`; không cần `adb reverse`.
3. Khi mở app lần đầu, ứng dụng tự tải file CSV cố định gồm **500.000 sản phẩm** từ backend và lưu riêng trên thiết bị. Đợi thông báo tải xong trước khi xử lý. Thời gian tải không nằm trong số đo; những lần mở sau có thể dùng lại file mà không cần mạng. Nếu tải thất bại, kiểm tra backend rồi nhấn **Xử lý file** để thử tải lại.
4. Tắt công tắc, nhấn **Xử lý file**, rồi quan sát đồng hồ và vòng xoay. Hai nút **Kiểm tra phản hồi** và **Hủy tác vụ nền** vẫn sáng, nhưng không phản hồi trong lượt chạy này vì công việc khóa luồng giao diện.
5. Bật công tắc và xử lý lại cùng file. Khi đang chạy, nhấn **Kiểm tra phản hồi**; số lần hiển thị ngay trên nút và chỉ tăng với những lần thực sự được xử lý lúc tác vụ còn chạy. Nút **Hủy tác vụ nền** có thể dừng lượt chạy ở chế độ này. Khi chưa chạy hoặc đã hoàn thành, chỉ nút **Xử lý file** được bật; trong lúc xử lý, hai nút còn lại được bật.
6. Nhấn từng thẻ **TẮT/BẬT TỐI ƯU** để xem chi tiết và so sánh mã SHA-256, qua đó xác nhận hai lượt xử lý cùng nội dung file. Biểu tượng làm lại cạnh tiêu đề chỉ xóa số đo trên màn hình, không xóa file.

## Số liệu có nghĩa gì?

- **Hoàn thành:** từ lúc bắt đầu tác vụ đến lúc đọc và phân tích xong, không tính thời gian tải file mẫu. Có thể bao gồm thời gian điều phối coroutine và chờ hệ thống.
- **Đọc file:** chỉ đo lúc đọc nội dung file mẫu vào bộ nhớ. **Kiểm tra và tính toán:** tính SHA-256, đọc CSV UTF-8, kiểm tra dòng hợp lệ và tính giá trị tồn kho. Hai chế độ dùng cùng mã và cùng điều kiện giới hạn file.
- **UI khựng ước lượng:** đồng hồ dùng `Handler` trên luồng giao diện theo nhịp 100 ms. Số đo là **độ trễ lớn nhất vượt 100 ms** trong lượt xử lý. Ví dụ nhịp tiếp theo đến sau 1.100 ms thì số khựng ước lượng là 1.000 ms. Số này có thể bị ảnh hưởng bởi lịch chạy của hệ thống, GC và tình trạng ứng dụng; không phải tổng thời gian khựng, độ trễ mỗi cú chạm, hay thống kê khung hình bị rớt. Dùng Android Studio Profiler/Frame rendering để quan sát sâu hơn.
- **Phản hồi:** số lần nút được xử lý trong lúc tác vụ còn chạy. Những lần chạm bị xếp hàng và chỉ xử lý sau khi kết thúc không được tính là phản hồi trong tác vụ.
- **Hợp lệ / dòng lỗi / giá trị tồn kho:** kết quả thực tế của file, giúp kiểm tra hai lượt làm cùng công việc. Lỗi định dạng CSV, header hoặc mã hóa dừng lượt chạy và hiển thị thông báo; dòng có số hoặc trường bắt buộc sai được tính là dòng lỗi.

OFF chạy phần đọc và xử lý trên luồng chính để minh họa tình huống xấu. ON dùng `Dispatchers.IO` cho đọc file, `Dispatchers.Default` cho tính toán và trở về luồng chính cập nhật màn hình. Tác vụ nền có kiểm tra hủy khi đọc và xử lý; scope được hủy khi Activity bị hủy. Trạng thái đo đang lưu trong bộ nhớ màn hình nên sẽ đặt lại khi Activity được tạo lại, ví dụ khi xoay màn hình. File mẫu đã tải vẫn còn.

Tác vụ chính chạy đủ lâu có thể gây hộp thoại **Ứng dụng không phản hồi (ANR)**, nhất là khi máy chậm. Đây là hành vi có chủ đích để minh họa chế độ OFF; nếu Android hiển thị hộp thoại, hãy chọn chờ tác vụ hoàn tất hoặc đóng demo. Không dùng kỹ thuật chạy file nặng trên UI thread trong ứng dụng thật. Độ trễ và thời gian tổng phụ thuộc thiết bị, GC, thứ tự chạy và bộ nhớ đệm file của hệ điều hành; nên lặp lại nhiều lần, đổi thứ tự hai chế độ và đo thêm trên thiết bị thật.

## Định dạng file mẫu

File mẫu dùng UTF-8, dấu phân cách `,`, header và thứ tự cột như ví dụ:

```csv
id,ten,danh_muc,gia,so_luong
1,"Bàn, gỗ",Gia dụng,20000,3
2,Sách Kotlin,Sách,15000,2
```

`id` và `gia` là số nguyên dương; `so_luong` là số nguyên không âm. Tên và danh mục không rỗng. Hỗ trợ dấu ngoặc kép CSV và xuống dòng trong ô. Giá trị tồn kho bằng tổng `gia × so_luong`; phép tính tràn số và dòng không hợp lệ được tính vào số dòng lỗi. Giới hạn file **64 MiB**, mỗi trường tối đa **4096 ký tự**, và tối đa **100 cột** mỗi dòng.

## Kiểm tra kỹ thuật

Trong `Backend`: `npm run test:file-demo` kiểm tra endpoint, số dòng và tính cố định của file. Trong App 5: `./gradlew.bat connectedDebugAndroidTest` kiểm tra bộ đọc CSV và khả năng hủy. Các kiểm tra có thể cần emulator và quyền chạy Gradle/ADB.

Tham khảo: [Android — Better performance through threading](https://developer.android.com/topic/performance/threads), [Coroutines và dispatcher](https://developer.android.com/kotlin/coroutines/coroutines-adv).
