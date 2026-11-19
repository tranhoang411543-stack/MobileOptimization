# Backend dùng chung cho các ứng dụng Android

Backend NestJS và PostgreSQL chạy trên máy tính để các ứng dụng demo dùng chung. Hiện tại API phục vụ tìm kiếm sách, phân trang, cache Room và demo giải mã ảnh đúng kích thước.

## Khởi động

Yêu cầu: Windows, Docker Desktop đang chạy, Node.js 20.19 trở lên và npm. Mở PowerShell trong thư mục `Backend` và chạy **một lệnh**:

```powershell
.\Start.cmd
```

Lệnh sẽ khởi động container PostgreSQL, chờ database sẵn sàng, cài thư viện nếu đây là lần đầu và chạy backend. Lần cài thư viện đầu tiên cần mạng; các lần sau không cần tải lại nếu `node_modules` còn nguyên. Giữ cửa sổ terminal mở. Nhấn `Ctrl+C` để dừng backend; container và dữ liệu vẫn được giữ lại.

Nếu đang đứng ở thư mục khác, có thể chạy một lệnh với đường dẫn đầy đủ:

```powershell
& 'C:\Project\Tối ưu phần mềm ứng dụng\Backend\Start.cmd'
```

Database chỉ mở trên `127.0.0.1:5549` của máy tính. Backend chỉ mở trên `127.0.0.1:3000`; Android Emulator truy cập bằng `10.0.2.2:3000`. Thông tin database trong mã là thông tin **chỉ dùng cho môi trường học tập local**, không sử dụng cho production.

## Dữ liệu mẫu

Khi Docker khởi tạo volume mới, file `database/001_initial.sql` tạo bảng `books` và 10.000 sách giả lập. Tìm thử `Android`, `Kotlin`, `Java`, `Database` hoặc tên tác giả. Dữ liệu được giữ qua những lần dừng/khởi động container. Script khởi tạo chỉ chạy khi volume PostgreSQL được tạo lần đầu.

## API

- `GET http://127.0.0.1:3000/api/health`: kiểm tra backend và kết nối database.
- `GET http://127.0.0.1:3000/api/health/keep-alive`: trả HTTP 204 không có nội dung, không truy vấn CSDL; App 1 dùng để cố gắng giữ kết nối khi màn hình đang hiển thị. Không dùng endpoint này thay cho kiểm tra sức khỏe CSDL.
- `GET /api/app3-sync/catalog`: trả tối đa 1.000 sách đầu cho danh mục gốc của App 3, gồm `timing.databaseMs`; không thay đổi giới hạn phân trang chung của App 2.
- `GET /api/app3-sync/state` và `GET /api/app3-sync/changes?epoch=...&after=...&limit=50`: kiểm tra phiên bản và lấy theo trang các sách mới tạo bằng script demo App 3. `epoch` bảo vệ trường hợp CSDL được tạo lại; `after` là mốc thay đổi, không phải ID sách.
- WebSocket `/api/app3-sync/events`: gửi tín hiệu sau khi thêm sách thành công; app vẫn lấy nội dung qua API thay đổi để có thể lấy bù khi mất kết nối.
- `POST /api/app3-sync/demo-books`: thêm một sách demo vào bảng `books` và ghi nhật ký đồng bộ trong cùng transaction. Trong terminal khác, chạy `.\Add-App3-Book.cmd` để gọi API này; có thể truyền `-Title`, `-Author` và `-Price`.
- `GET http://127.0.0.1:3000/api/books/search?q=Android&limit=20`: tìm trong tên sách hoặc tác giả, không phân biệt chữ hoa/thường. `q` có 1–100 ký tự; `limit` tùy chọn, từ 1 đến 50, mặc định 20. Tìm theo chuỗi chính xác có dấu: `Lập` và `Lap` không tương đương.
- `GET http://127.0.0.1:3000/api/books/catalog?mode=all`: trả toàn bộ sách mẫu cho chế độ không phân trang của App 2. Đây là luồng cố ý kém hiệu quả để trình diễn trên dữ liệu local, không nên áp dụng cho danh sách lớn trong ứng dụng thực tế.
- `GET http://127.0.0.1:3000/api/books/catalog?mode=paged&limit=20&afterId=0`: trả tối đa `limit` sách có mã lớn hơn `afterId`, theo thứ tự mã tăng dần. `limit` từ 1 đến 50, mặc định 20; `afterId` mặc định 0. Dùng `nextCursor` của response làm `afterId` cho trang kế tiếp khi `hasMore=true`.
- `GET http://127.0.0.1:3000/api/books/catalog?mode=paged&limit=20&page=1`: chọn trực tiếp trang đánh số từ 1, trả thêm `page`, `pageSize`, `total` và `totalPages`. Không dùng `page` cùng `afterId`. Trang vượt quá trang cuối được đưa về trang cuối; danh sách rỗng trả `page=1`, `totalPages=0`.

App 3 dùng API danh mục riêng cho cả hai chế độ. Backend ghi log thời gian CSDL cho danh mục và request đồng bộ; thời gian transaction bao gồm chờ kết nối và giao tiếp PostgreSQL.

- `GET http://127.0.0.1:3000/api/image-demo/products`: trả 40 sản phẩm giả lập và đường dẫn ảnh cho App 4.
- `GET http://127.0.0.1:3000/api/image-demo/images/<mã-ảnh>`: trả một trong 8 ảnh JPEG local kích thước 2000 × 1500 px. Các mã được giới hạn trong danh sách ảnh mẫu; hai endpoint trả `Cache-Control: no-store` để demo không vô tình so sánh cache HTTP.

Ảnh mẫu của App 4 nằm trong `assets/image-demo`, dùng lại 8 ảnh cho 40 sản phẩm và không cần tải từ Internet khi chạy. Nguồn và tác giả ảnh được ghi trong [ATTRIBUTION.md](assets/image-demo/ATTRIBUTION.md). Tên sản phẩm và giá chỉ để minh họa.

Backend tạo thêm `app3_sync_state` và `app3_sync_changes` theo cách không xóa dữ liệu có sẵn. Nhật ký chỉ theo dõi sách thêm qua script/API App 3; những thay đổi trực tiếp khác trong PostgreSQL không tự tạo tín hiệu. Không xóa volume để áp dụng thay đổi.

- `GET http://127.0.0.1:3000/api/file-demo/products.csv`: tạo luồng tải CSV UTF-8 cố định gồm 500.000 sản phẩm cho App 5. File được sinh theo từng phần và không cần thay đổi PostgreSQL; mỗi lần tải có cùng nội dung. File chỉ dùng để chuẩn bị dữ liệu, không nằm trong phép đo thời gian xử lý trên Android.

- `POST /api/transport-demo/submit`: nhận `username` và `password` giả lập do người dùng nhập. Mỗi request hợp lệ được ghi thành một dòng vào `app7_transport_submissions` và trả `recordId`; phản hồi không trả lại username/password. Đây không phải chức năng đăng nhập. Theo mục đích demo, password được lưu **nguyên văn** trong PostgreSQL: không dùng mật khẩu thật. Migration `database/004_app7_transport_submissions.sql` tạo bảng bổ sung khi backend khởi động mà không xóa volume. Ngoài HTTP cổng 3000, `Start.cmd` chuẩn bị chứng chỉ local để mở HTTPS demo cổng 3443 và cổng 3444 có chứng chỉ không tin cậy. Xem README App 7 để thử các chế độ và xem dữ liệu.

- `GET /api/app6-notes?limit=50&afterId=...`: lấy tổng số và danh sách tên, ID, cờ mã hóa, `createdAt` và `updatedAt`; không trả nội dung. Hai thời gian ở dạng ISO 8601 UTC; `updatedAt = null` nghĩa là chưa có thời gian chỉnh sửa được ghi nhận. `limit` từ 1 đến 100, mặc định 50; dùng `nextCursor` làm `afterId` để tải trang kế tiếp.
- `GET /api/app6-notes/:id`: lấy nội dung, cờ `encrypted` và `revision` của một ghi chú. `POST /api/app6-notes` tạo ghi chú từ `requestId` UUID, `title`, `content`, `encrypted`. Gửi lại cùng `requestId` và nội dung không tạo thêm dòng; dùng cùng mã cho nội dung khác trả HTTP 409.
- `PUT /api/app6-notes/:id`: cập nhật chính ghi chú đó từ `expectedRevision`, `title`, `content`, `encrypted`. Dùng `revision` từ lần đọc; backend trả HTTP 409 nếu dữ liệu đã bị thay đổi, thay vì ghi đè. Gửi lại cùng nội dung sau khi mất phản hồi được chấp nhận.

Migration `database/002_app6_notes.sql` tạo bảng `app6_notes`; `database/003_app6_note_updated_at.sql` bổ sung cột `updated_at` và trigger ghi thời gian khi tên, nội dung hoặc trạng thái mã hóa thay đổi. Các migration chạy khi backend khởi động, kể cả với volume đã có dữ liệu; không cần xóa volume. Thời gian sửa của các lần xảy ra trước migration không thể khôi phục. Backend lưu nguyên nội dung app gửi và không có khóa giải mã. Tên và cờ mã hóa ở dạng rõ; backend không có xác thực hay phân quyền cho demo này. Xem [README App 6](<../T6 - Mã hóa dữ liệu lưu trên máy bằng AES-GCM và Android Keystore/README.md>) để thử sửa bản mã và xem giới hạn.

Các chế độ danh sách trả `mode`, `items`, `returned`, `hasMore`, `nextCursor` và `timing.databaseMs`. Khi hết dữ liệu, `hasMore=false` và `nextCursor=null`. Cách phân trang bằng cursor đọc thêm tối đa một dòng trong CSDL để xác định còn trang tiếp theo. Cách chọn trang theo số đếm tổng và đọc trang trong cùng một giao dịch chỉ đọc, dùng `LIMIT`/`OFFSET` và không gửi toàn bộ danh sách. Nếu dữ liệu thay đổi giữa các request, cả hai cách đều không tạo một ảnh chụp cố định cho toàn bộ phiên.

Phản hồi tìm kiếm của App 1 gồm `query`, `items`, `returned` và `timing.databaseMs`. `databaseMs` là thời gian backend chờ truy vấn PostgreSQL, bao gồm chi phí lấy kết nối nếu cần; không phải thời gian từ lúc người dùng thao tác đến lúc thấy kết quả.

Response còn có header `X-Demo-Connection-Id` và `X-Demo-Connection-Request`: request đầu trên mỗi socket backend nhận được có số `1`, các request tiếp theo trên socket đó có số tăng dần. Đây là thông tin cho demo, không phải kết nối PostgreSQL. API tìm kiếm và danh sách trả `Cache-Control: no-store` để metadata của kết nối không bị dùng lại từ response cache. Sau khi cập nhật mã backend, hãy dừng backend đang chạy và chạy lại `Start.cmd`.

## Log thời gian từng request

Khi chạy `Start.cmd`, mỗi request HTTP có một dòng `[RequestTiming]` trong cửa sổ backend, kể cả request bị lỗi hoặc kết nối bị gián đoạn. Ví dụ:

```text
Nhận lúc 10:15:23.124 | GET "/api/books/search" | HTTP 200 | Backend: 18.45 ms | Kết nối: a1b2c3d4 · lần 2
```

`Backend` được đo từ khi request đi vào middleware đến khi Node.js hoàn tất ghi response ra hệ điều hành. Nếu kết nối bị ngắt trước đó, log ghi `Gián đoạn` và thời gian đến lúc ngắt. Thời gian này bao gồm thời gian chờ PostgreSQL của request tìm kiếm, nhưng không bao gồm thời gian truyền HTTP giữa emulator và backend hoặc phần xử lý giao diện trên app. Số `lần` là số request trên **kết nối HTTP đó**, không phải số thứ tự request trong phiên đo của app. Mã kết nối 8 ký tự và số lần tương ứng với chi tiết kết nối trên app.

Log chỉ xuất hiện trong terminal khi backend chạy; không tự lưu vào file. Đường dẫn được ghi không kèm tham số query hoặc nội dung request. Việc ghi log cũng có thể ảnh hưởng một phần đến kết quả đo hiệu năng.

### Xóa log khi backend vẫn đang chạy

Trong **chính cửa sổ terminal đang chạy backend**, nhập `cls` rồi nhấn Enter. Bạn cũng có thể nhập `clear`. Màn hình log được xóa, backend và PostgreSQL tiếp tục hoạt động; các request tiếp theo vẫn tạo log mới. Tùy terminal, lịch sử cuộn cũ cũng có thể được xóa. Lệnh này chỉ được bật khi backend chạy trong terminal tương tác; nếu chạy chuyển hướng đầu vào/đầu ra tới file hoặc qua tiến trình nền thì không có lệnh nhập này.

Sau khi cập nhật mã, cần dừng backend cũ và chạy lại `Start.cmd` một lần để nạp chức năng. Những lần xóa log tiếp theo không cần khởi động lại backend.

## Kiểm tra

Sau khi backend đã chạy, mở terminal khác trong thư mục này:

```powershell
npm run test:e2e
```

Để kiểm tra TypeScript: `npm run build`. Bài kiểm thử e2e sử dụng database thật của container và chỉ đọc dữ liệu mẫu.

Để kiểm tra phân loại socket mới và dùng lại, chạy `npm run test:connections`. Bài kiểm thử mở một backend tạm trên cổng trống và chỉ đọc dữ liệu mẫu từ PostgreSQL.

Để kiểm tra log thời gian và xử lý kết nối bị ngắt, chạy `npm run test:timing`. Bài kiểm thử dùng server HTTP cục bộ tạm thời và không cần PostgreSQL.

Để kiểm tra API danh sách, phân trang và các đầu vào không hợp lệ với PostgreSQL mẫu, chạy `npm run test:paging`. Bài kiểm thử mở một backend tạm trên cổng trống và chỉ đọc dữ liệu mẫu.

Để kiểm tra lệnh nhập trong terminal, chạy `npm run test:console`.

Để kiểm tra API ảnh, kích thước JPEG và mã ảnh không hợp lệ, chạy `npm run test:images`. Bài kiểm tra mở backend tạm trên cổng trống, không cần thay đổi PostgreSQL.
