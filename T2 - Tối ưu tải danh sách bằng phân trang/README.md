# T2 — Tối ưu tải danh sách bằng phân trang

Ứng dụng Android/Kotlin so sánh hai cách tải cùng danh sách sách mẫu từ backend local:

- **Tắt tối ưu:** nhấn **Tải danh sách** để một request trả toàn bộ sách trong bảng.
- **Bật tối ưu:** nhấn **Tải danh sách** để nhận tổng số sách và tối đa 20 sách của trang đầu. Sau khi tải thành công, các nút chọn trang xuất hiện thay cho nút tải; cuộn không tự tải trang mới.

App sử dụng `ListView` của Android để tái sử dụng view khi cuộn và tự điều phối việc tải trang, không dùng thư viện Paging 3. Việc phân trang xảy ra ở PostgreSQL/backend, không phải tải hết dữ liệu về app rồi mới chia nhỏ. Đây là demo local; API tải toàn bộ được giữ riêng để minh họa cách chưa tối ưu, không nên áp dụng cho danh sách lớn trong sản phẩm thực tế.

## Cách chạy

1. Mở Docker Desktop.
2. Nếu backend đang chạy bằng phiên bản cũ, nhấn `Ctrl+C` trong cửa sổ backend đó. Sau đó mở terminal trong thư mục `Backend` và chạy `./Start.cmd` (PowerShell có thể dùng `.\Start.cmd`). Giữ cửa sổ backend mở.
3. Mở thư mục **T2 - Tối ưu tải danh sách bằng phân trang** như một project riêng trong Android Studio, đợi Gradle Sync.
4. Chọn Android Emulator API 26 trở lên và chạy cấu hình `app` ở chế độ **debug**.

Ứng dụng kết nối `http://10.0.2.2:3000`, địa chỉ emulator dùng để truy cập localhost của máy tính. HTTP chỉ được bật tới địa chỉ này trong bản debug; không sử dụng cấu hình đó cho production.

## Cách trình bày

1. Mở app, để **Tắt tối ưu**, nhấn **Tải danh sách** và chờ tải xong. Ghi lại các số đo lần đầu trên card.
2. Bật công tắc, nhấn **Tải danh sách** rồi chờ trang đầu. So sánh số sách, dung lượng và thời gian hiển thị lần đầu với card Tắt.
3. Chọn số trang, nút trước hoặc nút sau trong card **Bật/Tắt tối ưu Paging**. Mỗi lần chọn trang khác tải và thay danh sách bằng trang đó; tổng số request, sách và dung lượng nhận tăng dần. Trên màn hình hẹp, cuộn ngang hàng nút để giữ kích thước mỗi nút là 40dp.
4. Nhấn từng card để xem số sách, dung lượng response, HTTP và CSDL theo từng request. Nút refresh xóa hai bộ số đo và đưa app về trạng thái chờ nhấn **Tải danh sách**. Nếu lỗi mạng, nhấn thông báo lỗi để thử lại.

Khi mở app, đổi chế độ hoặc nhấn refresh, app không tự gửi request danh sách: cần nhấn **Tải danh sách**. Đổi chế độ xóa danh sách hiện tại và bắt đầu phiên đo mới cho chế độ vừa chọn, đồng thời giữ bộ số đo của chế độ còn lại. Một request cũ đã bắt đầu vẫn có thể hoàn tất ở backend, nhưng phản hồi của phiên cũ không được ghép vào danh sách mới. Số liệu được giữ trong phiên hiện tại; tạo lại Activity (ví dụ xoay màn hình) hoặc đóng tiến trình sẽ bắt đầu đo lại và không giữ lịch sử.

## Ý nghĩa số đo

- **Sách đầu / Dữ liệu đầu:** số sách và số byte của body JSON thực tế trong response thành công đầu tiên. App yêu cầu `Accept-Encoding: identity` để phép đo hai chế độ nhất quán. Dung lượng không bao gồm HTTP headers, TCP/IP và không đại diện cho cấu hình nén thực tế của mọi hệ thống.
- **Hiển thị đầu:** thời gian xấp xỉ từ khi nhấn **Tải danh sách** đến sau lần vẽ đầu tiên có nội dung danh sách. Số đo bao gồm gọi HTTP, xử lý JSON và dựng giao diện, nhưng không bảo đảm đo chính xác thời điểm GPU/màn hình vật lý hoàn tất hiển thị. Nếu lỗi rồi nhấn thông báo để thử lại, thời gian vẫn tính từ lần nhấn tải ban đầu.
- **Heap Java trước → sau:** ảnh chụp lượng heap Java đang dùng ngay trước khi bắt đầu tải và sau lần vẽ đầu tiên. Đây không phải tổng RAM của app, không phải mức đỉnh; ART/GC và việc chuyển chế độ có thể làm số đo dao động, thậm chí giảm. Không tự ép GC. Muốn đo sâu hơn, dùng Android Studio Memory Profiler và lặp lại trên cùng điều kiện.
- **Tổng:** số lượt app bắt đầu thử HTTP, tổng số sách nhận thành công và tổng byte body của các response đã đọc trong phiên hiện tại. Khi đổi chế độ, kết quả của request cũ bị bỏ qua; log backend vẫn có thể ghi request đó. Lượt bị lỗi kết nối vẫn được tính, dù server có thể chưa nhận được request.
- **HTTP trong chi tiết:** từ lúc bắt đầu gọi đến khi đọc xong body; không bao gồm parse JSON hoặc dựng UI. **CSDL** là phần thời gian backend chờ PostgreSQL và là một phần của HTTP. Kết nối mới/đã dùng và cache của hệ điều hành có thể làm hai lần đo chênh lệch.

Đo nhiều lần ở cả hai chế độ và hoán đổi thứ tự chạy. Không suy ra Paging luôn giúp từng HTTP request nhanh hơn: lợi ích chính ở lần tải đầu là chỉ truyền và giữ lượng dữ liệu cần dùng; khi xem tất cả trang, chi phí request và metadata có thể làm tổng dữ liệu truyền lớn hơn chế độ tải một lần. Quay lại một trang đã xem sẽ tải lại và cộng thêm dữ liệu nhận trong phiên.

API chọn trang dùng số trang và `OFFSET`, nên có thể nhảy trực tiếp đến trang bất kỳ nhưng trang sâu và phép đếm tổng có chi phí CSDL. Tổng và dữ liệu của mỗi response được đọc trên cùng một ảnh chụp; nếu dữ liệu thay đổi giữa hai request, vị trí sách giữa các trang vẫn có thể thay đổi.
