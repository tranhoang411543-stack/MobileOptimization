# App 6: Ghi chú trên backend và AES-GCM

App cho phép tạo, xem và sửa nhiều ghi chú. Hai chế độ đều lưu trong bảng `app6_notes` của PostgreSQL: tên luôn được lưu dạng văn bản thuần; khi tắt mã hóa, nội dung cũng được lưu dạng văn bản thuần; khi bật, Android dùng AES-256-GCM để mã hóa nội dung trước khi gửi. Khóa bảo vệ khóa nội dung được giữ trong Android Keystore và yêu cầu sinh trắc học. Backend không có khóa và không giải mã ghi chú.

## Chạy ứng dụng

1. Mở Docker Desktop, chạy `Backend\Start.cmd`. Nếu backend đã chạy trước khi cập nhật mã, dừng và chạy lại để nạp API và tạo bảng mới. Migration chỉ bổ sung bảng; không xóa volume hoặc dữ liệu đã có.
2. Đăng ký vân tay hoặc sinh trắc học mạnh trên thiết bị trước khi demo. Emulator cần cài khóa màn hình và đăng ký vân tay giả lập trong cài đặt Android.
3. Mở thư mục App 6 bằng Android Studio và chạy cấu hình debug trên emulator API 26 trở lên. Emulator gọi backend qua `10.0.2.2:3000`. Android 9 trở lên dùng hộp thoại sinh trắc học hệ thống; Android 8 dùng hộp thoại vân tay.
4. Khi mở app hoặc quay lại màn hình chính, danh sách được tự động tải từ backend. Nếu không kết nối được, kiểm tra backend rồi mở lại màn hình chính.

App debug cho phép HTTP tới địa chỉ emulator ở trên. Bản release không tự cho phép HTTP; cần cấu hình backend và HTTPS phù hợp nếu dùng ngoài môi trường demo.

## Luồng demo

1. Nhấn **Tạo ghi chú** trên màn hình chính để mở biểu mẫu. Nhập tên và nội dung giả lập, để công tắc mã hóa **tắt**, rồi nhấn **Tạo ghi chú** trong biểu mẫu. Xác thực sinh trắc học để gửi; nếu hủy, biểu mẫu và nội dung đang nhập được giữ lại.
2. Tạo ghi chú khác với công tắc mã hóa **bật**. Sau khi xác thực ở nút tạo, app mã hóa nội dung ngay trên thiết bị, sau đó gửi bản mã lên backend.
3. Nhấn vào từng dòng trong danh sách. App yêu cầu sinh trắc học trước khi tải nội dung và chuyển sang màn hình xem. Nếu hủy hoặc xác thực không thành công, app ở lại danh sách. Với `encrypted = false`, nội dung từ backend được hiển thị sau xác thực; với `encrypted = true`, app dùng khóa đã mở để giải mã.
4. Danh sách trả tên, mã, trạng thái mã hóa và thời gian; nội dung của một ghi chú chỉ được tải khi nhấn vào dòng đó. Mỗi hàng hiển thị thời gian theo định dạng `hh:mm - dd/MM/yyyy`: dùng ngày tạo nếu chưa có lần sửa được ghi nhận, hoặc ngày sửa gần nhất, theo múi giờ của thiết bị. Mỗi trang có tối đa 50 ghi chú; nhấn **Tải thêm ghi chú** khi còn trang tiếp theo.
5. Sửa tên, nội dung hoặc công tắc rồi nhấn **Lưu ghi chú**. App không hỏi lại sinh trắc học trong phiên xem này; nếu bật mã hóa, app dùng khóa nội dung vừa mở để mã hóa lại trước khi gửi. Khi tắt, nội dung được gửi dạng rõ. App cập nhật cùng một ghi chú, không tạo thêm dòng. Khi quay lại danh sách và mở ghi chú lần khác, app yêu cầu xác thực lại.

Các thao tác đang chờ và lỗi được thông báo bằng Toast để không làm thay đổi vị trí các thành phần. Nếu một ghi chú đã bị thay đổi sau khi mở, app báo lỗi và giữ phần đang nhập; quay lại danh sách và mở lại trước khi lưu. App chưa có chức năng xóa. Hai file ghi chú local của phiên bản cũ nếu tồn tại không bị xóa và không tự chuyển lên backend.

Ngày tạo của các ghi chú cũ được giữ nguyên. Do phiên bản trước chưa ghi thời gian sửa, các lần sửa trước khi nâng cấp không có dữ liệu để khôi phục; ghi chú sẽ hiện ngày tạo cho đến khi có thay đổi mới.

## Thử bảo vệ nội dung khi chụp màn hình

Màn hình tạo, xem và sửa ghi chú có công tắc **Bảo vệ khi chụp màn hình**. Khi mở một màn hình ghi chú mới, công tắc mặc định bật; chế độ này độc lập với công tắc mã hóa AES-GCM. App bật `FLAG_SECURE` trước khi đưa nội dung ghi chú lên cửa sổ và giữ flag trong lúc chuyển từ ghi chú về danh sách. Flag được gỡ sau khi màn hình ghi chú đã ẩn. `FLAG_SECURE` áp dụng cho **toàn bộ cửa sổ ghi chú**, không chỉ vùng chữ; danh sách ghi chú không được flag này bảo vệ khi đã chuyển xong.

Chỉ dùng nội dung giả lập để demo. Mở hoặc tạo ghi chú, lần lượt tắt rồi bật công tắc và dùng **chức năng chụp của chính Android** để so sánh. Có thể dùng nút nguồn + giảm âm lượng trên thiết bị, hoặc trên emulator gửi phím chụp của Android bằng:

```powershell
adb -s emulator-5554 shell input keyevent KEYCODE_SYSRQ
```

Thay `emulator-5554` bằng mã emulator từ `adb devices` nếu khác. Android có thể lưu ảnh đen hoặc từ chối chụp khi bảo vệ bật; khi tắt, ảnh có thể nhìn thấy ghi chú. Ảnh do Android tạo được lưu trên thiết bị/emulator, không phải tự động lưu ở máy tính.

**Lưu ý với Android Studio:** Nút **Take Screenshot** trong cửa sổ **Running Devices** của emulator có thể lấy hình từ phía trình giả lập trên máy tính, ngoài đường chụp của Android. Trên Pixel 7 API 37 đã thử, nút này vẫn nhìn thấy ghi chú dù cửa sổ có `FLAG_SECURE`, trong khi ảnh do Android tạo che cửa sổ đó. App không thể dùng `FLAG_SECURE` để buộc công cụ phía máy tính che nội dung đang được emulator hiển thị. Không dùng nút này làm bằng chứng rằng Android đã hoặc chưa chặn screenshot; hãy kiểm tra ảnh chụp của Android hoặc dùng điện thoại thật.

Flag không xóa ảnh cũ và không ngăn người khác dùng thiết bị khác chụp màn hình; khả năng bảo vệ có thể khác nhau giữa thiết bị và phiên bản Android.

## Thử sửa bản mã trong PostgreSQL

Chỉ thực hiện với ghi chú **giả lập** bạn chủ động tạo để thử. Việc `UPDATE` dưới đây thay đổi nội dung lưu thật của ghi chú đã chọn; hãy sao chép giá trị `content` trước nếu muốn phục hồi.

Mở terminal khác trên máy chạy Docker:

```powershell
docker exec -it mobile-optimization-postgres-1 psql -U mobile_demo -d mobile_optimization
```

Xem danh sách và chọn đúng `id` của một ghi chú có `encrypted = true`:

```sql
SELECT id, title, encrypted, content FROM app6_notes ORDER BY id DESC;
```

Thay `123` bên dưới bằng ID vừa chọn. Câu lệnh đổi một ký tự Base64 của bản mã mà vẫn giữ JSON hợp lệ:

```sql
UPDATE app6_notes
SET content = jsonb_set(
    content::jsonb,
    '{ciphertext_and_tag}',
    to_jsonb(
        CASE WHEN left(content::jsonb ->> 'ciphertext_and_tag', 1) = 'A' THEN 'B' ELSE 'A' END
        || substr(content::jsonb ->> 'ciphertext_and_tag', 2)
    )
)::text
WHERE id = 123 AND encrypted = true;
```

Quay lại app và nhấn ghi chú đó. App nhận bản mã đã sửa, AES-GCM từ chối xác thực và app hiển thị **Toast không thể giải mã**. Các ghi chú khác không bị thay đổi.

## Khóa và giới hạn

- Với ghi chú được mã hóa theo cơ chế mới, khóa nội dung AES ngẫu nhiên được lưu dưới dạng đã mã hóa trong vùng riêng của app. Khóa dùng để bảo vệ nó nằm trong Android Keystore và yêu cầu xác thực sinh trắc học cho từng lần mở khóa. Sau xác thực, khóa nội dung được giữ trong bộ nhớ của phiên xem để mở và lưu chỉnh sửa; app xóa mảng khóa phiên khi rời biểu mẫu hoặc lưu thành công. Thao tác này không bảo đảm xóa ngay mọi bản sao tạm do bộ nhớ và thư viện mật mã quản lý. IV ngẫu nhiên mới được tạo cho mỗi lần mã hóa. Bản mã và tag được biểu diễn bằng Base64; Base64 không phải là mã hóa.
- Các ghi chú mã hóa cũ vẫn dùng khóa Keystore cũ và được app yêu cầu xác thực trước khi đọc. Khóa cũ không thể thay đổi thuộc tính để gắn sinh trắc học; khi sửa và lưu lại với mã hóa bật, ghi chú đó chuyển sang khóa nội dung được bảo vệ mới. Bản app cũ không đọc được các bản mã tạo theo cơ chế mới.
- Ghi chú mã hóa cần đúng khóa đã dùng để tạo. Thiết bị khác hoặc việc gỡ/cài lại ứng dụng có thể làm khóa cũ không còn, vì demo không có chức năng sao lưu hay khôi phục khóa. Khi mở một ghi chú, app không tự tạo khóa thay thế.
- Tên ghi chú và cờ `encrypted` lưu dạng rõ để hiển thị danh sách và chọn luồng đọc. AES-GCM trong demo xác thực bản mã và AAD của nội dung, không xác thực tên hay cờ `encrypted`; không dùng cờ này như bằng chứng dữ liệu không bị sửa.
- Sinh trắc học bảo vệ thao tác trong app, không xác thực hay phân quyền các yêu cầu trực tiếp tới backend. Backend local không triển khai tài khoản hay phân quyền cho ghi chú; HTTP debug cũng không bảo vệ đường truyền. Chỉ dùng dữ liệu giả lập và môi trường học tập. Lưu văn bản thuần nghĩa là backend nhận được nội dung ở dạng rõ.
- Nếu không nhận được phản hồi sau khi gửi, app cho phép thử lại cùng nội dung trong phiên hiện tại; backend nhận diện lần tạo hoặc cập nhật đã hoàn thành để tránh tạo trùng hay báo nhầm xung đột. Nếu thoát ứng dụng giữa lúc chưa xác định được kết quả, hãy mở lại danh sách để kiểm tra.

## Kiểm thử

- Android: `.\gradlew.bat -Papp6Validation=true :app:connectedDebugAndroidTest` khi đã kết nối emulator. Thuộc tính này dùng gói kiểm thử riêng, không thay dữ liệu app chính. Các kiểm thử bao gồm hủy/xác thực khi tạo và mở, lưu sửa không hỏi lại, tương thích bản mã cũ và yêu cầu xác thực của khóa Keystore. Kiểm thử với cảm biến thực cần thiết bị đã đăng ký sinh trắc học; kiểm thử giao diện với backend thật là bài opt-in cần fixture và PostgreSQL cô lập.
- Backend: `npm run test:app6-notes` chỉ chấp nhận database rỗng tên `app6_notes_test` và `MOBILE_DEMO_DB_PORT` riêng. Bài kiểm thử ghi dữ liệu và sửa bản mã trong database kiểm thử đó; không chạy trên database demo đang dùng.
