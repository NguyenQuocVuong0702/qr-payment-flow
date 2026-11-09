# Báo cáo giai đoạn 1

Đã đọc toàn bộ đề chính thức và hoàn thành phần phân tích, khởi tạo cùng B1.
Ngày kiểm chứng: **2026-11-09**, múi giờ +07:00.

## 1. Kiến trúc và cấu trúc

Java 21, Spring Boot 3.5.16, Maven Wrapper 3.3.4 chạy Maven 3.9.16, H2.
Một ứng dụng, các ranh giới merchant/gateway/wallet; Bank nằm trong Gateway.
Controller → service có transaction → repository. Common cung cấp Clock,
RestClient builder có timeout và error envelope. Gateway/Wallet hiện chỉ có
package-info mô tả ranh giới, chưa có nghiệp vụ.

Xem [cây thư mục trong README](../README.md#cấu-trúc) và
[phân tích B1–B8, bảng dữ liệu, transaction, HTTP, HMAC, CRC, IPN, đối soát](architecture.md).

## 2. Quyết định quan trọng

- Giữ description **tối đa 19** ký tự ASCII chữ/số/dấu cách, reject blank; không sửa
  quy tắc theo ví dụ dài sai của đề. Không trim hoặc tự ép kiểu input.
- Tiền long/BIGINT VND; chỉ nhận JSON integer 1000–500000000, từ chối số thực,
  chuỗi số và overflow.
- Sequence DB sinh orderId có ngày +07; PK và CHECK bảo vệ dữ liệu; không dùng
  bộ đếm RAM. Có version cho cập nhật đơn ở các bước sau. Sequence có thể nhảy số.
- Transaction B1 commit trước response 201. H2 dev dùng file, WRITE_DELAY=0 sau khi
  smoke test phát hiện rủi ro mất commit với chế độ ghi trễ khi dừng đột ngột.
  Test dùng memory riêng, Hibernate validate schema và test rollback sau flush.
- Giữ API B1 nguyên trạng. B1 không có request key nên không thể phân biệt retry với
  đơn mới có cùng nội dung. Hai POST giống nhau tạo hai đơn; không tự retry B1 sau
  timeout. Giới hạn này được ghi rõ, không tuyên bố B1 đã idempotent. B2 và B5 sẽ
  chống lặp bằng orderId/requestId đúng như đề.
- Không tạo FK/association qua ranh giới Merchant/Gateway/Wallet. Mọi giao tiếp các
  bước sau qua HTTP; riêng Bank dùng transaction nội bộ Gateway.

## 3. File đã tạo

Workspace ban đầu chỉ có đề bài; **không sửa file đề bài**. Danh sách 32 file nguồn,
cấu hình, wrapper, test và tài liệu được tạo:

```text
.gitattributes
.gitignore
.mvn/wrapper/maven-wrapper.properties
mvnw
mvnw.cmd
pom.xml
README.md
docs/architecture.md
docs/phase1-report.md
requests/b1.http
src/main/java/vn/vnpay/qr/QrPaymentApplication.java
src/main/java/vn/vnpay/qr/common/config/InfrastructureConfiguration.java
src/main/java/vn/vnpay/qr/common/error/ApiError.java
src/main/java/vn/vnpay/qr/common/error/ApiExceptionHandler.java
src/main/java/vn/vnpay/qr/merchant/api/CreateOrderRequest.java
src/main/java/vn/vnpay/qr/merchant/api/CreateOrderResponse.java
src/main/java/vn/vnpay/qr/merchant/api/OrderController.java
src/main/java/vn/vnpay/qr/merchant/config/MerchantConfiguration.java
src/main/java/vn/vnpay/qr/merchant/config/MerchantProperties.java
src/main/java/vn/vnpay/qr/merchant/domain/Order.java
src/main/java/vn/vnpay/qr/merchant/domain/OrderStatus.java
src/main/java/vn/vnpay/qr/merchant/repository/OrderIdGenerator.java
src/main/java/vn/vnpay/qr/merchant/repository/OrderRepository.java
src/main/java/vn/vnpay/qr/merchant/service/OrderService.java
src/main/java/vn/vnpay/qr/gateway/package-info.java
src/main/java/vn/vnpay/qr/wallet/package-info.java
src/main/resources/application.yml
src/main/resources/application-dev.yml
src/main/resources/schema.sql
src/test/java/vn/vnpay/qr/ApplicationContextTest.java
src/test/java/vn/vnpay/qr/merchant/OrderApiIntegrationTest.java
src/test/resources/application-test.yml
```

`target/` chứa build/report, `.build/` chứa cache, log và công cụ smoke test cục bộ;
hai thư mục được ignore, không phải source bàn giao. Chưa tạo Git repository/commit.

## 4. API và cách thử

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
.\mvnw.cmd spring-boot:run
```

Trong terminal khác:

```powershell
Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/merchant/orders `
  -ContentType application/json `
  -Body '{"amount":55000,"description":"Thanh toan ao thun"}'
```

201 Created: orderId, merchantId=MC001, amount, description, status=CREATED,
createdAt theo offset +07:00. Sai amount/description trả 400 với INVALID_AMOUNT/
INVALID_DESCRIPTION và envelope errorCode/message/timestamp. JSON hỏng dùng mã
kỹ thuật INVALID_REQUEST; lỗi bất ngờ dùng INTERNAL_ERROR, không lộ chi tiết DB.
Chưa có GET đơn hàng của B7.

[requests/b1.http](../requests/b1.http) có các ví dụ thành công, biên và lỗi.

## 5. Kết quả test thực tế

Môi trường chạy JDK Temurin 21.0.12.1; Wrapper đã được chạy thật. Nạp archive Maven
chính thức có kiểm checksum vào cache cục bộ để vượt lỗi tải TLS của PowerShell
trên máy hiện tại; không sửa Wrapper, không tắt xác minh TLS.

Test nền tảng `-Dtest=ApplicationContextTest test`: **1 test thành công**.
Sau đó chạy toàn bộ test B1. Lệnh kiểm chứng bản cuối:

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.build\maven-user-home'
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.build\repository" test package
```

```text
ApplicationContextTest:   1 test,  0 failures, 0 errors, 0 skipped
OrderApiIntegrationTest: 65 test,  0 failures, 0 errors, 0 skipped
Tổng:                   66 test,  0 failures, 0 errors, 0 skipped
BUILD SUCCESS
Total time: 30.041 s
```

Trong lệnh `test package`, Surefire nhận biết cấu hình test đã chạy và không chạy
lặp ở phase package; **66 là số test, không phải cộng số lần chạy**.
JAR: `target/qr-payment-flow-0.0.1-SNAPSHOT.jar`.

Kiểm tra bao gồm response chính xác sáu trường, dữ liệu đã commit, amount biên/
missing/null/sai kiểu/overflow, description biên/blank/dài/dấu/control character,
JSON không hợp lệ, error envelope, rollback sau INSERT, constraint DB, và 12 request
MockMvc đồng thời có 12 ID khác nhau. Không có test bị disable/skip.

Smoke test riêng chạy JAR qua **HTTP thật**, dùng H2 file với các option của profile
dev và file thử trong `.build/`, không sửa dữ liệu dev:

- POST hợp lệ → 201; amount=999 → 400 INVALID_AMOUNT; mô tả ví dụ dài trong đề →
  400 INVALID_DESCRIPTION.
- Dừng đột ngột tiến trình ngay sau 201; mở file H2 bằng tiến trình JDBC khác,
  xác nhận orderId, amount=55000, status=CREATED đã tồn tại.
- Khởi động lại cùng file H2; tạo đơn 1000 VND; dừng đột ngột ngay sau 201 lần nữa;
  SQL xác nhận **2 đơn, SUM(amount)=56000**, ID khác nhau
  (`ORD20261109001` và `ORD20261109033`, khoảng trống do sequence cache).
- Hai tiến trình ứng dụng thử đều đã dừng. Kiểm tra này xác nhận trường hợp dừng
  tiến trình đã thử, không phải chứng minh khả năng chịu mọi sự cố phần cứng.

Bằng chứng cục bộ:
[Maven log](../.build/final-verify.log),
[Surefire reports](../target/surefire-reports/),
[smoke log](../.build/smoke-test.log),
[smoke JSON](../.build/smoke-result.json).

## 6. Chưa thực hiện và kế hoạch B2

Chưa triển khai B2–B8, chưa seed merchant registry/account/wallet_user, chưa tạo
QR, HMAC, PIN, chuyển tiền, IPN, query, expiry job, đối soát hay refund. Chưa chạy
12 kịch bản end-to-end của đề; chưa có qr-parser trong workspace.

B2 tiếp theo: chốt điểm kích hoạt từ Merchant, thêm registry và payment_txn,
HTTP client có chữ ký, validation/idempotency/order conflict, buildTlv/CRC,
cập nhật WAITING_PAYMENT và kiểm chứng QR Type04 bằng qr-parser. Chạy toàn bộ
test B1+B2 và báo cáo trước khi bắt đầu B3.
