# T1 — Tối ưu tìm kiếm bằng Debounce

Ứng dụng Android/Kotlin so sánh hai cách tìm sách bằng cùng một API:

- **Tắt tối ưu:** bắt đầu một lượt gọi HTTP mỗi khi ô tìm kiếm thay đổi và có nội dung.
- **Bật tối ưu:** chỉ bắt đầu tìm khi không có thay đổi mới trong 400 ms.

Ứng dụng bỏ qua các phản hồi cũ để chúng không ghi đè kết quả của từ khóa hiện tại. Một yêu cầu đã bắt đầu vẫn có thể tiếp tục chạy trên server sau khi người dùng gõ thêm; Debounce chủ yếu ngăn yêu cầu chưa cần thiết được bắt đầu.

## Cách chạy

1. Mở Docker Desktop.
2. Mở terminal trong thư mục `Backend`, chạy ` .\Start.cmd ` và giữ cửa sổ đó mở.
3. Trong Android Studio, mở **thư mục này** như một project Android; chờ Gradle Sync.
4. Chọn một Android Emulator (API 26 trở lên) và chạy cấu hình `app` ở chế độ **debug**.

Ứng dụng kết nối `http://10.0.2.2:3000`, là địa chỉ Android Emulator dùng để truy cập localhost của máy tính. HTTP chỉ được cho phép đến địa chỉ này trong bản debug; ứng dụng được thiết kế để demo local, không phải cấu hình triển khai production.

Sau khi cập nhật tính năng giữ kết nối, hãy dừng backend cũ và chạy lại `Start.cmd` để nạp endpoint mới.

## Giữ kết nối HTTP

Khi màn hình App 1 hiển thị, ứng dụng gửi một request nhỏ `GET /api/health/keep-alive` ngay lúc mở, rồi khoảng 2 giây sau mỗi lần request trước hoàn tất. Endpoint trả HTTP 204, không tìm sách và không truy vấn CSDL. Khi rời màn hình, app dừng đặt lịch; một request đã bắt đầu vẫn có thể hoàn tất. Khi quay lại, app thử làm ấm kết nối tiếp. Nếu backend tạm thời không sẵn sàng, app tiếp tục thử khi màn hình còn hiển thị.

Các request giữ kết nối **không tính vào số yêu cầu tìm kiếm** hay lịch sử trong hai card. Backend vẫn ghi log mỗi heartbeat, với đường dẫn `/api/health/keep-alive`; vì vậy tổng request trong log lớn hơn số request tìm kiếm trên giao diện. Mã và số thứ tự kết nối vẫn có trong log backend, trong đó số thứ tự bao gồm cả heartbeat.

Đây là cơ chế cố gắng giữ ít nhất một kết nối sẵn dùng, không bảo đảm mọi request sẽ sử dụng lại kết nối. Một request có thể cần kết nối mới nếu người dùng tìm ngay khi app đang khởi tạo, có nhiều tìm kiếm đồng thời, mạng thay đổi, hoặc một phía đóng socket. Việc gửi request đều đặn cũng phát sinh lưu lượng, ghi log và dùng pin; khoảng 2 giây được chọn để minh họa với backend local, không phải khuyến nghị mặc định cho ứng dụng thực tế.

Để kiểm tra, chờ khoảng 7–10 giây sau khi mở màn hình rồi tìm `Android`; đối chiếu mã và số thứ tự kết nối của các dòng heartbeat và tìm kiếm trong terminal backend. Đưa app xuống nền và chờ vài giây: các heartbeat mới sẽ dừng. Đưa app trở lại để tiếp tục thử.

## Trình diễn

1. Nhấn **Làm lại** để xóa hai bộ số đo.
2. Để công tắc **Tắt**, gõ nhanh `Android` và đợi kết quả xuất hiện.
3. Bật công tắc, gõ lại `Android` theo nhịp tương tự và chờ kết quả.
4. Nhấn vào card **TẮT TỐI ƯU** hoặc **BẬT TỐI ƯU** để xem thời gian của từng request, từ khóa và trạng thái. Đổi chế độ sẽ xóa ô tìm kiếm nhưng giữ số đo; nút **Làm lại** xóa số đo của cả hai chế độ.

Khi gõ từng ký tự nhanh hơn 400 ms, chế độ bật thường bắt đầu ít yêu cầu hơn. Nếu bạn ngừng gõ quá 400 ms giữa các ký tự, chế độ bật vẫn có thể bắt đầu nhiều yêu cầu. Không giả định trước kết quả đo: hãy thực hiện và ghi lại số liệu của máy bạn.

## Ý nghĩa số đo

- **Yêu cầu:** số lần ứng dụng bắt đầu thử gọi HTTP trong phiên đo, kể cả lần lỗi kết nối; đây là số phía client và không khẳng định server đã nhận đủ từng yêu cầu. Đợi các yêu cầu cũ hoàn tất trước khi ghi kết quả.
- **Kết quả (HTTP):** thời gian từ khi bắt đầu gọi HTTP đến khi đọc xong phản hồi. Số đo bao gồm truyền dữ liệu và backend xử lý; không tính thời gian chờ Debounce, phân tích JSON hay cập nhật giao diện. Trước đây chỉ số này được ghi riêng là `HTTP`, nên nay được gộp với `Kết quả` để tránh trùng lặp.
- **CSDL:** thời gian backend chờ truy vấn PostgreSQL, có thể bao gồm thời gian lấy kết nối từ pool. Đây là một phần của thời gian `Kết quả`, không cộng hai số lại với nhau.
- **Tổng HTTP (N):** cộng thời gian HTTP của `N` request đã hoàn tất và có số đo, kể cả request lỗi có thời gian đo được. Request đang chạy hoặc bị gián đoạn khi xoay màn hình chưa có số đo hoàn tất không được tính.
- **Tổng CSDL (N):** cộng thời gian CSDL của `N` request có số đo CSDL hợp lệ. Request lỗi không cung cấp số đo CSDL sẽ không được tính; vì vậy `N` có thể khác số request HTTP hoặc tổng số yêu cầu đã bắt đầu.

`Kết quả` và `CSDL` trên card vẫn là của **lần tìm kiếm hiện tại gần nhất thành công**. Hai dòng tổng cộng các request của từng chế độ từ lần **Làm lại** gần nhất, bao gồm cả phản hồi cũ hoàn tất muộn; không chỉ tính từ khóa hiện tại. Các request có thể chạy đồng thời nên tổng thời gian HTTP không nhất thiết bằng thời gian người dùng chờ thực tế. `Tổng CSDL` là một phần thời gian xử lý của các request HTTP, không cộng hai tổng lại với nhau.

Danh sách chi tiết ghi cả request đã hoàn thành muộn, lỗi hoặc đang chạy; kết quả cũ không ghi đè giao diện tìm kiếm. Mỗi chế độ giữ lịch sử trong bộ nhớ cho đến khi nhấn **Làm lại**; lịch sử được giữ khi xoay màn hình, nhưng sẽ mất nếu tiến trình ứng dụng bị dừng. Số lượt `Yêu cầu` được đếm tại ứng dụng và không tính riêng các lần thư viện HTTP tự thử lại ở tầng vận chuyển.
