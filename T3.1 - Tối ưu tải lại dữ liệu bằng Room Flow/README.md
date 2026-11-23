# T3.1 — Tối ưu tải lại dữ liệu bằng Room Flow (cách hiện đại)

Phiên bản hiện đại của [T3](<../T3 - Tối ưu tải lại dữ liệu bằng Cache và Room/README.md>). Ứng dụng dùng **cùng backend** (`/api/app3-sync/*`, WebSocket `/api/app3-sync/events`), **cùng giới hạn 1.000 sách, cùng mốc 60 giây** và **cùng các chỉ số trên card** (Đã mở, Tải danh mục, Dữ liệu, Nguồn, Hiển thị, Sách) như T3.

T3 đã dùng Room, nên khác biệt chính nằm ở **cách dữ liệu đi từ Room lên giao diện**: T3.1 dùng Room làm nguồn dữ liệu duy nhất, và giao diện chỉ quan sát `Flow` của Room.

| Thành phần | T3 (truyền thống) | T3.1 (hiện đại) |
|---|---|---|
| Đọc Room | DAO đồng bộ + `Executor` + `Handler.post` | DAO `suspend` + `Flow` |
| Cập nhật màn hình khi đồng bộ ghi sách mới | Bộ đếm `contentChanged` → listener → đọc lại Room bằng tay | Room tự phát lại `Flow` sau mỗi lần ghi, giao diện tự vẽ lại |
| Ô "Dữ liệu lưu trong Room" | Đọc toàn bộ 1.000 dòng để đếm, chạy lại mỗi lần `onResume` | Truy vấn `COUNT(*)` dạng `Flow`, tự cập nhật |
| Tải từ server, chế độ bật | Lưu vào Room, rồi đọc lại và hiển thị | Chỉ ghi vào Room; `Flow` phát danh sách mới |
| Bộ máy đồng bộ | Cờ `running/pending`, token `generation`, `synchronized`, `Handler.postDelayed` | Kênh `CONFLATED`, hủy coroutine, `delay` |
| WebSocket | `WebSocketListener` + đếm token + `postDelayed` để kết nối lại | `callbackFlow` + `retryWhen` |
| Điều kiện chạy đồng bộ | Tự đếm Activity trong `ActivityLifecycleCallbacks` | `ProcessLifecycleOwner` + `combine` + `collectLatest` |
| Lưu công tắc | `SharedPreferences` | DataStore |
| Gọi HTTP | `HttpURLConnection` + `org.json` | Retrofit `suspend` + kotlinx.serialization |
| Giao diện, điều hướng | 2 Activity + XML + `ListView` | Compose + Navigation, `ViewModel` theo từng màn hình |
| Rời màn hình khi đang tải | Request vẫn chạy đến hết, kết quả bị bỏ | Coroutine bị hủy, OkHttp hủy luôn request |

Cơ chế an toàn khi xóa cache vẫn giống T3: mỗi trang đồng bộ được ghi trong một transaction kèm phép kiểm tra `CatalogSyncState`. Nếu cache vừa bị xóa thì trang cũ bị từ chối. Nhờ có kiểm tra này ở tầng CSDL, T3.1 không cần token `generation` trong bộ nhớ.

## Cách chạy

1. Mở Docker Desktop, chạy `Backend\Start.cmd` (giống T3).
2. Mở **thư mục này** bằng Android Studio, chạy cấu hình `app` (debug) trên emulator API 26 trở lên.
3. Thêm sách bằng `Backend\Add-App3-Book.cmd` để thử đồng bộ. Khi app đang mở và bật tối ưu, số sách tự tăng mà không phải bấm gì.

Kiểm thử (không cần emulator, dùng Robolectric và Room trong bộ nhớ): `gradlew testDebugUnitTest`. Bộ test kiểm tra 4 điều:
- chưa có cache thì không gọi API đồng bộ;
- sau khi đồng bộ 3 sách mới (2 trang), `Flow` của Room phát cả sách gốc lẫn sách mới, và mốc đồng bộ được lưu;
- trang đồng bộ cũ không ghi lại được vào cache vừa bị xóa;
- backend được tạo lại (epoch đổi) thì cache được thay bằng bản đầy đủ mới.

## Kết quả đo trên emulator (Pixel 8, API 36, cùng backend, 23/11/2026)

Kịch bản: xóa dữ liệu app → mở danh mục ở chế độ tắt → bật tối ưu → mở danh mục 3 lần. Đã có sẵn 1 sách thêm bằng script, nên cache có 1.001 sách. Mỗi bản chạy **một lần**, nên số liệu chỉ mang tính tham khảo.

| Chỉ số | T3 (debug) | T3.1 (release + R8) |
|---|---|---|
| Tắt tối ưu: hiển thị / HTTP | 743 ms / 559 ms | 931 ms / 894 ms |
| Bật, lần 1 (chưa có cache): hiển thị / HTTP | 669 ms / 589 ms | 968 ms / 893 ms |
| Bật, lần 2 và 3 (đọc Room): hiển thị | 151 ms, 60 ms | 86 ms, 82 ms |
| Bật, lần 2 và 3: request tải danh mục | 0 | 0 |
| Dữ liệu tải danh mục (cả 4 lượt) | 113,7 KiB × 2 | 113,7 KiB × 2 |

Cách đọc kết quả:
- Ở cả hai bản, **tác dụng của cache gần như nhau**: đọc Room nhanh hơn tải server khoảng 5–10 lần và không tốn byte mạng. Room Flow **không làm đọc cache nhanh hơn**; nó làm **mã gọn và đúng hơn**, vì giao diện không bao giờ hiển thị dữ liệu cũ sau khi đồng bộ.
- Lần tải server đầu tiên của T3.1 chậm hơn khoảng 300 ms, chủ yếu do HTTP (khởi tạo OkHttp và Retrofit, kết nối đầu tiên). Đây là chi phí khởi động lạnh của thư viện, cần nêu khi đánh giá.

## Số liệu đo trên máy phát triển

| Chỉ số | T3 | T3.1 |
|---|---|---|
| Dòng mã Kotlin (không tính dòng trống, chú thích, test) | 1.078 | 993 (đã gồm toàn bộ giao diện) |
| Dòng XML layout | 149 | 0 |
| APK debug | 2,6 MiB | 11,0 MiB |
| APK release (T3 không R8, T3.1 có R8) | 1,82 MiB | **1,39 MiB** |

T3 đã kèm Room và OkHttp, nên khi bật R8, bản T3.1 (dù thêm Compose và Retrofit) vẫn **nhỏ hơn** bản T3 không R8.

## Lưu ý

- **Bản release không gọi được backend local**, vì cấu hình cho phép HTTP tới `10.0.2.2` chỉ nằm trong `src/debug` (T3 cũng vậy). Để kiểm tra R8 không làm hỏng Retrofit hay kotlinx.serialization, nhóm đã build tạm một bản release có cấu hình này và chạy thử. Số liệu ở bảng trên lấy từ bản đó.
- T3 có bước nâng cấp cache 30 sách cũ lên 1.000 sách (`catalog_base_1000`) và migration Room 1→2. T3.1 là app mới nên bỏ phần này, CSDL bắt đầu từ phiên bản 1.
- *Hiển thị* được ghi sau khung hình Compose đầu tiên có danh sách (`withFrameNanos`). Nguồn và số sách được chốt ngay lần hiển thị đầu, để sách đồng bộ về ngay sau đó không làm sai số đo.
