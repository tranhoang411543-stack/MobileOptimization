CREATE TABLE books (
    id integer PRIMARY KEY,
    title text NOT NULL,
    author text NOT NULL,
    price_vnd integer NOT NULL CHECK (price_vnd > 0)
);

-- Dữ liệu giả lập xác định, dễ tìm bằng Android, Kotlin, Java, Database...
INSERT INTO books (id, title, author, price_vnd)
SELECT n,
       (ARRAY[
           'Lập trình Android thực hành',
           'Kotlin từ cơ bản đến ứng dụng',
           'Java và cấu trúc dữ liệu',
           'Thiết kế giao diện Android',
           'Database và PostgreSQL',
           'Tối ưu hiệu năng ứng dụng',
           'Bảo mật ứng dụng di động',
           'Xây dựng ứng dụng với Kotlin'
       ])[1 + ((n - 1) % 8)] || ' - Tập ' || lpad(n::text, 5, '0'),
       (ARRAY[
           'Nguyễn Minh Anh',
           'Trần Hoàng Nam',
           'Lê Phương Thảo',
           'Phạm Gia Bảo',
           'Đỗ Quỳnh Chi'
       ])[1 + ((n - 1) % 5)],
       45000 + ((n * 17000) % 250000)
FROM generate_series(1, 10000) AS n;
