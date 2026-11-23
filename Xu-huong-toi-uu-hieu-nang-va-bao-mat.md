# Xu hướng nghiên cứu và phát triển gần đây về tối ưu hiệu năng và bảo mật trên Android

## 1. Tổng quan

Các kỹ thuật được nhóm triển khai trong T1–T7 (Debounce, phân trang, cache, giải mã ảnh theo kích thước hiển thị, đưa tác vụ nặng ra luồng nền, mã hóa AES-GCM và HTTPS) đều là những nguyên lý nền tảng đã được sử dụng từ lâu. Điểm thay đổi trong những năm gần đây chủ yếu nằm ở **công cụ, thư viện và cách nền tảng Android hỗ trợ** các nguyên lý đó.

- **Hiệu năng:** chuyển từ việc lập trình viên tự viết thủ công sang việc để thư viện và hệ thống tự tối ưu; đồng thời chuyển từ đánh giá cảm tính sang đo lường bằng số liệu.
- **Bảo mật:** bảo mật được bật mặc định trên nền tảng, giảm dần sự phụ thuộc vào mật khẩu và bắt đầu chuẩn bị cho mật mã hậu lượng tử.

## 2. Tối ưu hiệu năng

| Thời gian | Phát triển | Liên quan đến demo |
|---|---|---|
| 05/2017 | Google ra mắt thư viện **Room** và công bố Kotlin là ngôn ngữ chính thức cho Android | T3 |
| 10/2018 | **Kotlin Coroutines 1.0** ra mắt, sau đó trở thành cách xử lý bất đồng bộ được Google khuyến nghị | T5 |
| 2020 | **AsyncTask bị loại bỏ (deprecated)** từ Android 11; **Paging 3** ra bản alpha (ổn định 05/2021) | T5, T2 |
| 07/2021 | **Jetpack Compose 1.0** – bộ công cụ giao diện khai báo thay thế XML | Giao diện |
| 2021–2022 | **Macrobenchmark** để đo khởi động và độ giật khung hình; **Baseline Profiles** giúp biên dịch trước mã thường dùng, cải thiện thời gian khởi động khoảng 20–30% | Đo lường |
| 04/2023 | **R8 full mode** trở thành mặc định từ Android Gradle Plugin 8.0, thu nhỏ và tối ưu mã mạnh hơn | Kích thước ứng dụng |
| 2024 | **Kotlin 2.0 (trình biên dịch K2)**; **Compose strong skipping** bật mặc định, giảm số lần vẽ lại giao diện; **Android 15** hỗ trợ trang bộ nhớ **16 KB** giúp tăng tốc khởi động và tiết kiệm pin | Giao diện, bộ nhớ |
| 06/2025 | **Android 16**: cải tiến Android Runtime (ART); `ProfilingManager` hỗ trợ **profiling do hệ thống kích hoạt**; `getStartComponent()` hỗ trợ tối ưu luồng khởi động; chế độ tương thích trang 16 KB | Đo lường |
| 02/2027 | Google Play **bắt buộc bản cập nhật ứng dụng hỗ trợ trang bộ nhớ 16 KB** (mốc ban đầu là 11/2025, sau đó được lùi lại) | Ứng dụng có mã native |

## 3. Tối ưu bảo mật

| Thời gian | Phát triển | Liên quan đến demo |
|---|---|---|
| 2013 | **Android Keystore** (Android 4.3) – lưu khóa mật mã trong vùng an toàn của hệ thống | T6 |
| 08/2018 | **TLS 1.3** được chuẩn hóa (RFC 8446), bắt tay nhanh hơn và loại bỏ các thuật toán yếu; **Android 9** chặn lưu lượng HTTP không mã hóa theo mặc định | T7 |
| 2019 | **Android 10** bật TLS 1.3 mặc định; thư viện `security-crypto` (EncryptedSharedPreferences) ra mắt | T7, T6 |
| 2022 | **Photo Picker** (Android 13) – ứng dụng chỉ truy cập ảnh người dùng chọn; Apple, Google, Microsoft cùng cam kết hỗ trợ **Passkeys** | Quyền riêng tư, xác thực |
| 11/2023 | **Credential Manager** ổn định – hợp nhất passkey, mật khẩu và đăng nhập liên kết | Xác thực |
| 2024 – 01/2025 | **Play Integrity API** thay thế hoàn toàn SafetyNet Attestation, giúp phát hiện ứng dụng bị sửa đổi hoặc thiết bị đã root | Chống giả mạo |
| 08/2024 | **NIST** công bố các chuẩn **mật mã hậu lượng tử** (FIPS 203 – ML-KEM, FIPS 204, FIPS 205); trình duyệt bắt đầu áp dụng ML-KEM trong TLS | Hướng nghiên cứu |
| 04/2025 | Google **loại bỏ thư viện `security-crypto`** (phiên bản 1.1.0-alpha07); khuyến nghị dùng trực tiếp `KeyGenerator` với AndroidKeyStore | T6 |
| 06/2025 | **Android 16**: mặc định chống tấn công chuyển hướng Intent; yêu cầu **quyền truy cập mạng cục bộ**; API **chia sẻ khóa Keystore** giữa các ứng dụng; kiểm soát truy cập GPU chặt chẽ hơn | T6, T7 |

## 4. So sánh kỹ thuật truyền thống và hiện đại trong các demo

| Demo | Cách nhóm triển khai | Cách hiện đại tương ứng | Nhận xét |
|---|---|---|---|
| T1 – Debounce | `Handler.postDelayed` + `TextWatcher` | `Flow.debounce()` + `collectLatest` | Cách hiện đại ngắn gọn hơn, tự hủy yêu cầu cũ |
| T2 – Phân trang | Tự quản lý số trang, `LIMIT/OFFSET` | **Paging 3** (kết hợp keyset pagination ở máy chủ) | Paging 3 tự xử lý tải thêm, thử lại và trạng thái lỗi |
| T3 – Cache | Room + `Executor` | Room với `suspend` DAO / `Flow` (offline-first) | Giao diện tự cập nhật khi dữ liệu thay đổi |
| T4 – Ảnh | `BitmapFactory` + `inSampleSize` | **Coil / Glide** | Thư viện tự giải mã đúng kích thước và có cache bộ nhớ, ổ đĩa |
| T5 – Luồng nền | **Coroutines** + `Dispatchers.IO/Default` | `viewModelScope` / `lifecycleScope` | Đã theo hướng hiện đại |
| T6 – Mã hóa | **AES-GCM + Android Keystore** tự triển khai | Đúng khuyến nghị sau khi `security-crypto` bị loại bỏ (04/2025) | Đã theo hướng hiện đại |
| T7 – HTTPS | `HttpsURLConnection` + Network Security Config, quan sát được **TLS 1.3** | OkHttp/Retrofit + `CertificatePinner` | Có thể bổ sung certificate pinning |

**Nhận xét:** Cách triển khai thủ công phù hợp với mục đích học tập vì thể hiện rõ cơ chế bên trong của từng kỹ thuật. Đối với ứng dụng thực tế, các thư viện hiện đại giúp giảm lượng mã, hạn chế lỗi, tự quản lý vòng đời và được Google tiếp tục bảo trì.

## 5. Hướng phát triển

- Chuyển T1 sang `Flow.debounce()`, T2 sang Paging 3, T4 sang Coil để so sánh với cách thủ công.
- Bổ sung certificate pinning cho T7 bằng OkHttp.
- Đo hiệu năng bằng Macrobenchmark và Android Studio Profiler trên thiết bị thật, trong nhiều điều kiện mạng khác nhau.
- Tìm hiểu đăng nhập bằng Passkeys (Credential Manager) và kiểm tra toàn vẹn ứng dụng bằng Play Integrity API.

## Tài liệu tham khảo

1. Android Developers – Android 16 summary: https://developer.android.com/about/versions/16/summary
2. Android Developers Blog – Android 16 is here (06/2025): https://android-developers.googleblog.com/2025/06/android-16-is-here.html
3. Android Developers Blog – Prepare Play apps for devices with 16 KB page size (05/2025): https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html
4. Android Developers – Support 16 KB page sizes: https://developer.android.com/guide/practices/page-sizes
5. Android Developers – androidx.security release notes: https://developer.android.com/jetpack/androidx/releases/security
6. Android Developers – androidx.security.crypto package summary: https://developer.android.com/reference/androidx/security/crypto/package-summary
7. ProAndroidDev – Goodbye EncryptedSharedPreferences: A 2026 Migration Guide: https://proandroiddev.com/goodbye-encryptedsharedpreferences-a-2026-migration-guide-4b819b4a537a
8. IETF – RFC 8446: The Transport Layer Security (TLS) Protocol Version 1.3 (2018): https://www.rfc-editor.org/rfc/rfc8446
9. NIST – FIPS 203: Module-Lattice-Based Key-Encapsulation Mechanism Standard (2024): https://csrc.nist.gov/pubs/fips/203/final
