# T7.1 — Bảo vệ dữ liệu truyền qua mạng bằng OkHttp và Certificate Pinning (cách hiện đại)

Phiên bản hiện đại của [T7](<../T7 - Bảo vệ dữ liệu truyền qua mạng bằng HTTPS/README.md>). Ứng dụng dùng **cùng backend** (`/api/transport-demo/submit`, cổng HTTP 3000, HTTPS tin cậy 3443, HTTPS không tin cậy 3444) và **cùng các kịch bản demo** như T7, để so sánh công bằng.

T7 đã dùng HTTPS và network_security_config để tin CA demo. T7.1 thêm **Certificate Pinning** bằng OkHttp `CertificatePinner` (pin SHA-256 public key của CA/chứng chỉ) và cải tiến cách gọi mạng.

| Thành phần | T7 (truyền thống) | T7.1 (hiện đại) |
|---|---|---|
| Gọi HTTP/HTTPS | `HttpURLConnection`, `org.json` | Retrofit `suspend`, OkHttp, kotlinx.serialization |
| Tin CA thử nghiệm | `network_security_config` debug-overrides | `network_security_config` debug-overrides (giống T7) |
| Certificate Pinning | Không có | `CertificatePinner` của OkHttp (pin SHA-256 public key) |
| Xử lý lỗi chứng chỉ | `try/catch` trên `HttpsURLConnection` | `try/catch` trên Retrofit `suspend`, OkHttp tự chặn |
| Thực thi mạng | `Executor` + `Handler.post` | `suspend` function + coroutine |
| Giao diện | XML layout + `Activity` | Jetpack Compose |
| Hủy request khi rời màn hình | Không tự động (cần disconnect thủ công) | Coroutine bị hủy, OkHttp hủy request |

T7.1 **không thay đổi backend**, giữ nguyên các cổng và chứng chỉ demo của T7, để đánh giá khách quan.

## Cách thức Certificate Pinning

Certificate Pinning là kỹ thuật "ghim" (pin) fingerprint của chứng chỉ hoặc public key mà app tin cậy ngay trong mã nguồn. Khi kết nối HTTPS, nếu chứng chỉ server **không khớp** với pin đã lưu, kết nối bị **từ chối ngay lập tức**, kể cả khi chứng chỉ đó hợp lệ theo chuỗi tin cậy hệ thống.

**Cách T7.1 pin:**
- Pin SHA-256 của **public key CA demo** (Backend/.local-tls/app7/root-ca.pem), được tính bằng lệnh OpenSSL (xem mã nguồn `TransportClient.kt`).
- Áp dụng `CertificatePinner` cho hostname `10.0.2.2` (emulator) khi pinning bật.
- Khi bật pinning và gửi request HTTPS, OkHttp **kiểm tra chứng chỉ server có khớp pin không**. Nếu không → `SSLPeerUnverifiedException`, request bị chặn trước khi gửi nội dung.

**Lợi ích:**
- Bảo vệ chống tấn công MITM (man-in-the-middle) ngay cả khi kẻ tấn công cài CA độc hại vào hệ điều hành. Pin sai → bị chặn.
- Tăng cường bảo mật cho các kết nối nhạy cảm (thanh toán, dữ liệu y tế, v.v.).

**Rủi ro vận hành:**
- **Xoay vòng chứng chỉ:** Nếu server đổi chứng chỉ (hết hạn, đổi CA), pin cũ không khớp → app không kết nối được. Cần cập nhật app với pin mới.
- **Backup pin:** Nên có ít nhất 2 pin (chứng chỉ hiện tại + chứng chỉ dự phòng) để không bị gián đoạn khi xoay vòng.
- **Ngày hết hạn:** Chứng chỉ demo có thời hạn 825 ngày (server) và 3650 ngày (CA). Hết hạn → server không khởi động được hoặc app từ chối.
- **Pin phụ thuộc CA demo:** Pin trong mã nguồn T7.1 khớp với CA demo sinh khi chạy backend lần đầu. Nếu xóa `.local-tls/app7` và tạo lại CA mới, pin sẽ khác và app cần rebuild với pin mới.

**Lựa chọn khai báo:** OkHttp `CertificatePinner` (pin qua mã) vs `<pin-set>` trong `network_security_config.xml` (pin qua XML). T7.1 dùng `CertificatePinner` vì dễ bật/tắt động và dễ test. `<pin-set>` yêu cầu rebuild, nhưng tách pin ra khỏi mã (dễ audit, không thay đổi mã khi đổi pin).

## Chuẩn bị và chạy

1. **Backend:** Mở Docker Desktop, chạy `Backend\Start.cmd` như T7. Backend sẽ tạo CA và chứng chỉ demo nếu chưa có (xem T7 README).
2. **Chứng chỉ:** CA demo đã được chép từ T7 (`app/src/debug/res/raw/app7_demo_ca.pem`). Nếu backend tạo lại CA, hãy build lại app để đồng bộ.
3. **Pin:** Pin SHA-256 trong mã (`TransportClient.kt`) khớp với CA demo ban đầu. Nếu CA thay đổi, lấy pin mới bằng lệnh:
   ```bash
   openssl x509 -in Backend/.local-tls/app7/root-ca.pem -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl enc -base64
   ```
   Rồi cập nhật `caDemoPin` trong `TransportClient.kt` và rebuild.
4. **Chạy:** Mở **thư mục này** bằng Android Studio, chạy cấu hình `app` (debug) trên emulator API 26 trở lên.

Emulator truy cập host qua `10.0.2.2`, giống T7. Các cổng giống T7 (HTTP 3000, HTTPS 3443, HTTPS không tin cậy 3444).

## Các bước demo

1. Nhập username và password giả lập (vd `demo_student` / `DEMO_ONLY_123`).
2. **HTTP:** Tắt HTTPS, nhấn **Gửi dữ liệu giả lập**. Backend lưu bản ghi, card HTTP hiển thị kết quả.
3. **HTTPS không pinning:** Bật HTTPS, tắt "Dùng chứng chỉ không tin cậy" và tắt "Bật Certificate Pinning". Nhấn gửi → kết nối cổng 3443, tin CA qua network_security_config (giống T7).
4. **HTTPS với pinning:** Bật HTTPS và bật "Bật Certificate Pinning", tắt "Dùng chứng chỉ không tin cậy". Nhấn gửi → kết nối cổng 3443, OkHttp kiểm tra pin. Pin đúng → thành công, card hiển thị "📌 Pinned".
5. **Chứng chỉ không tin cậy, không pinning:** Bật HTTPS và "Dùng chứng chỉ không tin cậy", tắt pinning. Nhấn gửi → cổng 3444, Android từ chối (không có CA này trong network_security_config).
6. **Chứng chỉ không tin cậy, có pinning:** Bật HTTPS, "Dùng chứng chỉ không tin cậy" và "Bật Certificate Pinning". Nhấn gửi → cổng 3444, OkHttp kiểm tra pin → **pin sai** (chứng chỉ 3444 không khớp CA demo) → `SSLPeerUnverifiedException`, chặn trước khi gửi nội dung.

Nhấn card HTTP/HTTPS để xem chi tiết 30 lần gần nhất. Mỗi lần có pin sẽ hiển thị "📌 Certificate Pinning bật".

Nút **Làm lại** xóa nội dung nhập và số liệu, giữ công tắc.

## Xem dữ liệu trong PostgreSQL

Giống T7, chạy PowerShell trong `Backend`:
```powershell
docker compose exec -T postgres psql -U mobile_demo -d mobile_optimization -c "SELECT id, username, password, created_at FROM app7_transport_submissions ORDER BY id DESC LIMIT 20;"
```

Mã `id` tương ứng với mã bản ghi trên app. **Chỉ dùng dữ liệu giả lập.**

## Quan sát bằng Wireshark

Giống T7:
1. Bắt loopback traffic, filter `tcp.port == 3000 || tcp.port == 3443 || tcp.port == 3444`.
2. HTTP (3000): body JSON hiển thị plaintext.
3. HTTPS (3443/3444): body được mã hóa TLS, không đọc được nếu không giải mã.
4. Chứng chỉ không tin cậy hoặc pin sai: bắt tay thất bại, không có HTTP request.

## Kiểm thử

**Unit test JVM (không cần emulator):**
```bash
./gradlew testDebugUnitTest
```

Bộ test dùng OkHttp `MockWebServer` + `okhttp-tls` `HeldCertificate` để tạo chứng chỉ tự ký và test:
1. HTTP request thành công không cần pinning.
2. HTTPS với pin **đúng** → thành công.
3. HTTPS với pin **sai** → `SSLPeerUnverifiedException`, `certificateRejected = true`.
4. Backend trả response không hợp lệ → `successful = false`.

Kết quả test xuất hiện ở cuối báo cáo.

## Số liệu đo trên máy phát triển

| Chỉ số | T7 | T7.1 |
|---|---|---|
| Dòng mã Kotlin (không tính dòng trống, chú thích) | 365 | 476 |
| Dòng XML layout | 62 | 0 |
| APK debug | ~0.86 MB | ~9.81 MB |
| APK release (T7 không R8, T7.1 có R8) | ~0.63 MB | ~1.24 MB |

> **Ghi chú:** T7.1 dùng Jetpack Compose nên không có file XML layout, toàn bộ giao diện và logic hiển thị nằm trong Kotlin. Bản debug của T7.1 lớn hơn do chứa Compose runtime; nhưng bản release qua R8 được tối ưu chỉ còn ~1.24 MB.

## Kết quả đo trên emulator

> **Chưa đo trên emulator** (theo yêu cầu, không khởi động emulator trong quá trình tạo project).

## Lưu ý

- **Bản release không gọi được backend local** (giống T7), vì cấu hình HTTP cleartext và CA demo chỉ nằm trong `src/debug`. Để test R8, cần tạm thời build bản release có cấu hình này.
- **Pin phụ thuộc CA demo:** Nếu backend tạo lại CA (xóa `.local-tls/app7`), pin trong mã sẽ không khớp. Người dùng khác cần lấy pin mới (xem mục **Chuẩn bị và chạy**) và rebuild.
- **Không dùng production:** Chứng chỉ demo, pin và cấu hình này chỉ dành cho học tập. Không cài CA demo vào hệ điều hành, không dùng cho ứng dụng thực tế.
- **Pinning trong production:** Nên có ít nhất 2 pin (chứng chỉ hiện tại + backup), theo dõi ngày hết hạn, và có kế hoạch cập nhật app khi xoay vòng chứng chỉ.
- **Lựa chọn `<pin-set>`:** Nếu muốn pin qua XML thay vì mã, thêm `<pin-set>` vào `network_security_config.xml` (xem [Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)). Không test trong T7.1 vì khó bật/tắt động.

## So sánh T7 vs T7.1

| Khía cạnh | T7 (HttpsURLConnection) | T7.1 (OkHttp + Pinning) |
|---|---|---|
| **Mã gọi mạng** | `HttpURLConnection`, thủ công `connect()`, `OutputStream`, đọc stream | Retrofit `suspend`, tự động serialize/deserialize |
| **Coroutine/Executor** | `Executor` + `Handler.post` | `suspend` + coroutine, hủy tự động |
| **Tin CA demo** | `network_security_config` | `network_security_config` (giống) |
| **Pinning** | Không có | `CertificatePinner` pin SHA-256 public key |
| **Bảo vệ MITM** | Chỉ dựa vào hệ thống CA | Pin ngăn chặn CA độc hại |
| **Dòng mã** | 365 Kotlin + 62 XML layout (tổng 427 dòng) | 476 Kotlin (0 dòng XML, đã gồm UI Compose) |
| **Giao diện** | XML + `Activity` | Compose |
| **Test** | AndroidTest (cần emulator) | JUnit + MockWebServer (không cần emulator) |

**Kết luận:** T7.1 hiện đại hơn, mã gọn hơn, dễ test hơn, và **bảo mật tốt hơn** nhờ Certificate Pinning. Tuy nhiên, pinning đòi hỏi quản lý vận hành cẩn thận (theo dõi hết hạn, backup pin, cập nhật app).
