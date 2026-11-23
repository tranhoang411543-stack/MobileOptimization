# T2.1 — Tối ưu tải danh sách bằng Paging 3 (cách hiện đại)

Phiên bản hiện đại của [T2](<../T2 - Tối ưu tải danh sách bằng phân trang/README.md>). Ứng dụng dùng **cùng backend, cùng bảng sách, cùng kích thước trang 20 và cùng các chỉ số trên card** như T2 (Sách đầu, Dữ liệu đầu, Hiển thị đầu, Heap Java, Tổng request/sách/byte).

| Thành phần | T2 (truyền thống) | T2.1 (hiện đại) |
|---|---|---|
| Thư viện phân trang | Tự viết: số trang, nút chọn trang, `PageWindow` | **Jetpack Paging 3** (`Pager`, `PagingSource`) |
| Cách xem thêm | Bấm nút chọn trang, trang mới **thay** trang cũ | Cuộn gần cuối, Paging **tự tải và nối thêm** |
| Truy vấn máy chủ | `LIMIT 20 OFFSET (page-1)*20` + `COUNT(*)` | Keyset: `WHERE id > afterId ORDER BY id LIMIT 20` (không COUNT, không OFFSET) |
| Danh sách | `ListView` + `BaseAdapter` | Compose `LazyColumn` + `collectAsLazyPagingItems` |
| Trạng thái tải/lỗi | Tự quản lý `isLoading`, `failedPage` | `LoadState` (refresh/append) + `retry()` có sẵn |
| Gọi HTTP | `HttpURLConnection` + `Executor` | Retrofit `suspend` + OkHttp |
| Xoay màn hình | Mất danh sách và số đo | ViewModel giữ danh sách, `cachedIn` giữ các trang đã tải |

## Hai chế độ

- **Tắt tối ưu:** nhấn **Tải danh sách**, một request `mode=all` trả toàn bộ sách (giống T2).
- **Bật tối ưu:** nhấn **Tải danh sách**, Paging 3 tải 20 sách đầu (`afterId=0`). Khi người dùng cuộn tới còn 5 sách cuối (`prefetchDistance = 5`), Paging gửi request tiếp với `afterId` là mã sách cuối đã nhận.

Cấu hình `PagingConfig` được chọn để lần tải đầu so sánh được với T2:
- `initialLoadSize = 20` (mặc định của Paging là 3 × pageSize = 60);
- `enablePlaceholders = false`;
- không đặt `maxSize`, nên trang đã tải được giữ lại và không cần tải ngược (`prevKey = null`).

## Cách chạy

1. Mở Docker Desktop, chạy `Backend\Start.cmd`. Backend đã có sẵn endpoint keyset `GET /api/books/catalog?mode=paged&afterId=…`, không cần sửa.
2. Mở **thư mục này** bằng Android Studio, chạy cấu hình `app` (debug) trên emulator API 26 trở lên.

Kiểm thử đơn vị (không cần emulator): `gradlew testDebugUnitTest`. Bộ test dùng `TestPager` của `paging-testing` để kiểm tra:
- trang đầu có đúng 20 sách và key trang sau là mã sách cuối;
- cuộn đến hết thì `nextKey = null`, không gửi thêm request;
- lỗi mạng trả `LoadResult.Error` và được ghi vào số đo.

## Trình diễn so sánh với T2

1. Ở cả T2 và T2.1: nhấn Làm lại, tải danh sách ở chế độ tắt rồi bật tối ưu. So sánh **Sách đầu, Dữ liệu đầu, Hiển thị đầu, Heap**.
2. Xem 3 trang:
   - T2: bấm trang 2, rồi trang 3;
   - T2.1: cuộn xuống cho đến khi có 60 sách.

   So sánh *Tổng request/sách/byte* và thời gian *CSDL* trong *Xem chi tiết*. Truy vấn keyset không cần `COUNT(*)` nên CSDL thường nhanh hơn, nhất là ở trang sâu.
3. Xoay màn hình khi đang ở bật tối ưu: T2 mất danh sách, T2.1 giữ nguyên.

## Số liệu đo trên máy phát triển (không cần chạy app)

| Chỉ số | T2 | T2.1 |
|---|---|---|
| Dòng mã Kotlin (không tính dòng trống, chú thích) | 569 | 619 (đã gồm toàn bộ giao diện) |
| Dòng XML layout | 237 | 0 |
| APK debug | 0,87 MiB | 10,3 MiB |
| APK release (T2 không R8, T2.1 có R8) | 653 KiB | 1,14 MiB |

## Khác biệt cần nêu khi đánh giá

- **Cách xem thêm khác nhau:** T2 thay trang, nên chỉ giữ 20 sách. T2.1 nối thêm, nên số sách trong bộ nhớ tăng khi cuộn. Muốn giới hạn bộ nhớ thì đặt `maxSize`, nhưng khi đó backend phải có thêm truy vấn tải ngược (`beforeId`).
- **Keyset không nhảy được tới trang bất kỳ** và không có tổng số trang. Đổi lại, truy vấn không phải bỏ qua N dòng như `OFFSET`, và kết quả không bị lệch khi có sách mới được thêm vào giữa hai lần tải.
- **Hiển thị đầu** ở T2.1 được ghi sau khung hình Compose đầu tiên có danh sách (`withFrameNanos`). Ở T2, nó được ghi sau `onDraw` của `ListView`. Hai cách đo tương đương nhau nhưng không giống hệt.
- Bản release dùng R8. Cần chạy thử trên emulator trước khi lấy số liệu kích thước.
