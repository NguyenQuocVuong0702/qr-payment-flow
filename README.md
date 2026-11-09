# QR Payment Flow — Giai đoạn 1

Ứng dụng bài tập Java 21, Spring Boot 3.5.16, Maven 3.9.16, H2.
Hiện đã triển khai **B1: Merchant tạo đơn hàng**. B2–B8 mới có thiết kế.

- [Đề bài chính thức](bai-tap-luong-thanh-toan-qr.md)
- [Phân tích B1–B8, mô hình dữ liệu, transaction và các quyết định](docs/architecture.md)
- [Request chạy thử B1](requests/b1.http)
- [Báo cáo giai đoạn 1 và kết quả kiểm chứng thực tế](docs/phase1-report.md)

## Cấu trúc

```text
.mvn/wrapper/maven-wrapper.properties   Maven và checksum cố định
mvnw / mvnw.cmd                       Wrapper chính thức Apache
pom.xml
src/main/java/vn/vnpay/qr/
  QrPaymentApplication.java
  common/config/                      Clock, RestClient builder, JSON strict
  common/error/                       Error envelope và advice
  merchant/
    api/                              Controller và request/response DTO
    config/                           merchantId cục bộ MC001
    domain/                           Order, OrderStatus
    repository/                       JPA repository, sequence cấp orderId
    service/                          Transaction tạo đơn
  gateway/package-info.java           Ranh giới cho giai đoạn sau
  wallet/package-info.java            Ranh giới cho giai đoạn sau
src/main/resources/
  application.yml
  application-dev.yml                 H2 file, H2 console local
  schema.sql                          Chỉ orders + sequence của B1
src/test/java/vn/vnpay/qr/
  ApplicationContextTest.java
  merchant/OrderApiIntegrationTest.java
src/test/resources/application-test.yml
docs/architecture.md
requests/b1.http
```

Các thành phần giao tiếp qua HTTP khi B2 trở đi được triển khai. Bank sẽ nằm trong
Gateway và dùng chung transaction cơ sở dữ liệu. Không có bảng, endpoint hay seed
Gateway/Wallet/Bank trong phiên bản B1.

## Chạy

Cần JDK 21, JAVA_HOME trỏ đến JDK và kết nối Maven Central cho lần tải đầu.
Không cần cài Maven riêng. Nếu chưa có JAVA_HOME trong PowerShell:

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
.\mvnw.cmd spring-boot:run
```

Linux/macOS:

```bash
sh mvnw spring-boot:run
```

API: `http://localhost:8080`. Mặc định profile `dev`, chỉ bind 127.0.0.1 cho bài tập.
H2 giữ dữ liệu qua lần khởi động ở `data/qr-payment.mv.db`, không ghi file này vào Git.
`WRITE_DELAY=0` yêu cầu H2 đồng bộ ghi file khi commit, tránh cửa sổ mất commit do
chế độ ghi trễ mặc định nếu tiến trình bị dừng đột ngột ngay sau phản hồi 201.
H2 console: `http://localhost:8080/h2-console`, JDBC URL
`jdbc:h2:file:./data/qr-payment`, user `sa`, password mặc định rỗng cho dev local.
Có thể đặt `QR_DB_PASSWORD` trước lần tạo DB đầu tiên. Không bật console cho truy cập từ xa.

## API B1

`POST /api/merchant/orders`, `Content-Type: application/json`

```json
{"amount":55000,"description":"Thanh toan ao thun"}
```

`201 Created` (orderId và createdAt phụ thuộc lần chạy):

```json
{
  "orderId":"ORD20261109001",
  "merchantId":"MC001",
  "amount":55000,
  "description":"Thanh toan ao thun",
  "status":"CREATED",
  "createdAt":"2026-11-09T09:00:00+07:00"
}
```

PowerShell:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/merchant/orders `
  -ContentType application/json `
  -Body '{"amount":55000,"description":"Thanh toan ao thun"}'
```

Linux/macOS:

```bash
curl -i http://localhost:8080/api/merchant/orders \
  -H 'Content-Type: application/json' \
  -d '{"amount":55000,"description":"Thanh toan ao thun"}'
```

| Trường hợp | HTTP / mã |
|---|---|
| amount là số nguyên JSON từ 1.000 đến 500.000.000, description hợp lệ | 201 |
| Thiếu/null amount, sai kiểu, số thực, chuỗi số, overflow, ngoài khoảng | 400 INVALID_AMOUNT |
| Thiếu/null description, rỗng/blank, >19, dấu/ký tự lạ, sai kiểu | 400 INVALID_DESCRIPTION |
| Body JSON hỏng, rỗng, không phải object, duplicate key, trailing token | 400 INVALID_REQUEST |
| Lỗi server bất ngờ | 500 INTERNAL_ERROR |

Mô tả chỉ gồm `[A-Za-z0-9 ]`, không tự trim. Ví dụ gốc
`Thanh toan don hang ao thun` dài hơn 19 ký tự nên **phải bị từ chối**.
Error response luôn có ba trường:

```json
{"errorCode":"INVALID_AMOUNT","message":"amount must be an integer between 1000 and 500000000 VND","timestamp":"2026-11-09T09:00:00+07:00"}
```

B1 chưa có khóa request phía client trong đề: hai POST giống nội dung tạo hai đơn
riêng, không tự retry B1 sau timeout. Không gộp đơn theo amount/description. Chi tiết
giới hạn idempotency này và kế hoạch B2/B5 ở tài liệu thiết kế. B1 không gọi Gateway
và không tạo QR. `GET /api/merchant/orders/{orderId}` thuộc B7, chưa có.

## Kiểm thử

```powershell
.\mvnw.cmd test
```

```bash
sh mvnw test
```

Test tự chọn profile `test`, H2 memory tách khỏi H2 file dev; dùng fixed Clock,
không bọc test HTTP trong transaction để xác nhận việc commit thật. Test kiểm tra
biên/kiểu dữ liệu, response và persistence, rollback sau INSERT, ràng buộc DB và
12 request tạo đơn đồng thời. Báo cáo máy đọc được ở `target/surefire-reports/`.
12 kịch bản B1–B8 trong đề chưa thuộc kết quả của giai đoạn 1.

Kết quả kiểm chứng ngày 2026-11-09: **66 test, 0 failures, 0 errors, 0 skipped**;
đóng gói JAR thành công. Smoke test HTTP thật và H2 file qua hai lần dừng tiến trình
đột ngột xác nhận giữ được 2 đơn đã commit, tổng 56.000 VND, mã đơn không trùng.
Chi tiết lệnh và bằng chứng ở [báo cáo giai đoạn 1](docs/phase1-report.md).

Môi trường triển khai hiện tại giới hạn ghi ngoài workspace, nên lệnh kiểm chứng dùng
cache cục bộ (không thay đổi cấu hình Maven của người dùng):

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.build\maven-user-home'
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.build\repository" test
```

Máy hiện tại lỗi TLS khi script PowerShell của Wrapper tải archive Maven; archive
chính thức đã được tải bằng Python HTTPS, kiểm SHA-512 của Maven Central và SHA-256
trong wrapper properties, rồi nạp vào cache cục bộ. Các lần test sau vẫn chạy qua
`mvnw.cmd`; không tắt kiểm chứng TLS hoặc checksum, không sửa script Wrapper.

## Tiếp theo

B2: Gateway registry/payment_txn, HMAC, TLV, CRC, HTTP từ Merchant, replay/order
conflict và kiểm QR Type04. Chưa triển khai scan, PIN, chuyển tiền, IPN, tra cứu,
job hết hạn, đối soát hoặc hoàn tiền. Workspace ban đầu chưa là Git repository;
giai đoạn này không tự tạo Git history/commit.
