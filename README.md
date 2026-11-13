# QR Payment Flow — B1–B6

Ứng dụng bài tập Java 21, Spring Boot 3.5.16, Maven 3.9.16, H2.
Đã triển khai **B1–B6**: tạo đơn, QR động, scan, xác minh PIN, chuyển tiền giả lập
trong một transaction Gateway/Bank và IPN bền vững tới Merchant. **Chưa triển khai B7–B8**.

- [Đề bài chính thức](bai-tap-luong-thanh-toan-qr.md)
- [Phân tích B1–B8, mô hình dữ liệu, transaction và các quyết định](docs/architecture.md)
- [Request chạy thử B1](requests/b1.http)
- [Báo cáo giai đoạn 1 và kết quả kiểm chứng thực tế](docs/phase1-report.md)
- [Báo cáo B2, quyết định kỹ thuật và kết quả kiểm chứng](docs/phase2-report.md)
- [Request B2](requests/b2.http)
- [Báo cáo B3 và thiết kế quyền GET dùng chung](docs/phase3-report.md)
- [Request B3](requests/b3.http)
- [Thiết kế B4–B5](docs/b4-b5-design.md), [báo cáo mốc B4](docs/phase4-report.md)
- [Báo cáo B5: transaction, concurrency, test và restart](docs/phase5-report.md)
- [Request B4–B5](requests/b4-b5.http)
- [Báo cáo B6: outbox, retry và phục hồi crash](docs/phase6-report.md)
- [Request B6](requests/b6.http)

## Cấu trúc

```text
.mvn/wrapper/maven-wrapper.properties   Maven và checksum cố định
mvnw / mvnw.cmd                       Wrapper chính thức Apache
pom.xml
src/main/java/vn/vnpay/qr/
  QrPaymentApplication.java
  common/config/                      Clock, RestClient builder, JSON strict
  common/crypto/                      Canonicalization và HMAC-SHA256
  common/qr/                          CRC dùng chung
  common/error/                       Error envelope và advice
  merchant/
    api/                              Controller và request/response DTO
    client/                           RestClient và DTO riêng phía Merchant
    config/                           MC001, URL và secret Gateway cục bộ
    domain/                           Order, OrderStatus
    repository/                       JPA repository, sequence cấp orderId
    service/                          B1; điều phối B2 và transaction đọc/lưu riêng
  gateway/
    api/                              POST payments, confirm; GET có phân quyền
    bank/                             Account, ledger, Bank nội bộ cùng transaction
    ipn/                              Outbox, attempt log, claim/lease, RestClient, worker
    domain/                           Merchant registry, PaymentTxn, PaymentStatus
    repository/                       Các repository chỉ Gateway sử dụng
    service/                          Tạo/replay và phục hồi unique-key race
    qr/                               TLV động, CRC-16/CCITT-FALSE
  wallet/
    api/                              POST scan và DTO
    qr/                               Parser TLV, CRC, tách 62/05
    client/                           RestClient và DTO riêng của Wallet
    config/                           URL và credential đọc Gateway
    service/                          So amount, status và expireAt qua Clock
    domain/, repository/              Wallet User, BCrypt hash và khóa PIN bền vững
src/main/resources/
  application.yml
  application-dev.yml                 H2 file, H2 console local
  schema.sql                          orders, merchant, payment_txn, wallet_user,
                                      account, ledger_entry, idempotency, ipn_outbox,
                                      ipn_log, sequence
  data.sql                            Seed MC001 nếu chưa tồn tại
src/test/java/vn/vnpay/qr/
  ApplicationContextTest.java
  merchant/OrderApiIntegrationTest.java
  merchant/MerchantPaymentHttpIntegrationTest.java
  gateway/CryptoAndQrTest.java
  gateway/GatewayPaymentApiIntegrationTest.java
  gateway/QrParserCompatibilityIT.java Kiểm tra ngoài qua API qr-parser gốc
  gateway/ConfirmationIntegrationTest.java Money, concurrency, fault injection
  gateway/RestartPersistenceIT.java    Hai process thật, HTTP và H2 file qua restart
  gateway/IpnIntegrationTest.java      HMAC, HTTP, retry, concurrency và fault injection
  gateway/IpnRestartIT.java            Ba process thật: READY, mất ACK, thu hồi lease
  wallet/PaymentQrParserTest.java
  wallet/WalletScanHttpIntegrationTest.java
  wallet/WalletPaymentHttpTest.java    PIN, counter, lock, HTTP timeout
src/test/resources/application-test.yml
docs/architecture.md
requests/b1.http
requests/b2.http
requests/b3.http
```

Merchant → Gateway hiện gọi qua HTTP thật, không import service/repository/entity
của nhau. Bank nằm trong Gateway và dùng chung transaction cơ sở dữ liệu ở B5;
đã có Bank nội bộ, account, ledger, wallet_user và IPN qua HTTP tới Merchant.

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

Merchant dùng `QR_GATEWAY_BASE_URL` (mặc định `http://localhost:${server.port}`) và
`QR_MERCHANT_SECRET` (mặc định secret giả lập `secret-mc001`). Registry MC001 có secret
mẫu theo đề; khi thay secret cần cập nhật cả hai phía. Seed chỉ insert khi thiếu,
không ghi đè registry đã có. Với `server.port=0`, cấu hình URL Gateway rõ ràng sau khi
biết cổng thật; dev thông thường dùng cổng cố định 8080.

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

## API B2 và luồng Merchant → Gateway → Merchant

```mermaid
sequenceDiagram
    participant C as Client
    participant M as Merchant
    participant G as Gateway
    C->>M: POST /orders (B1)
    M-->>C: 201 CREATED + orderId
    C->>M: POST /orders/{orderId}/payments, expireMinutes
    M->>M: Đọc đơn trong transaction ngắn, kết thúc transaction
    M->>G: HTTP POST /api/gateway/payments + HMAC
    G->>G: Xác thực, tạo/replay payment_txn, commit
    G-->>M: 201 PENDING + QR, X-Signature
    M->>M: Verify response; transaction lưu txnRef + WAITING_PAYMENT
    M-->>C: 201 với cùng dữ liệu Gateway
```

### Kích hoạt từ Merchant

`POST /api/merchant/orders/{orderId}/payments`

```json
{"expireMinutes":15}
```

Chỉ nhận expireMinutes từ 1 đến 60. Amount, description và merchantId lấy từ đơn
đã lưu. Body có thêm amount/description hoặc trường lạ bị từ chối; không thay đổi B1.

PowerShell, sau khi ứng dụng đang chạy:

```powershell
$order = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/merchant/orders `
  -ContentType application/json -Body '{"amount":55000,"description":"Thanh toan ao thun"}'
$payment = Invoke-RestMethod -Method Post `
  -Uri "http://localhost:8080/api/merchant/orders/$($order.orderId)/payments" `
  -ContentType application/json -Body '{"expireMinutes":15}'
$payment
```

### API Gateway theo đề

`POST /api/gateway/payments`

```json
{
  "merchantId":"MC001",
  "orderId":"ORD20261102001",
  "amount":55000,
  "description":"Thanh toan ao thun",
  "expireMinutes":15,
  "signature":"bdda985548c27afd42ef3b29e6b868b767e1926e54ae52989e197321321e03ff"
}
```

Signature trên dành đúng body ví dụ và secret mẫu. Bỏ signature, sort key tăng dần,
nối `key=value` bằng `&`, giữ nguyên dấu cách, UTF-8 rồi HMAC-SHA256 lowercase hex.
Merchant tự tính chữ ký khi gọi endpoint kích hoạt phía trên.

Cả hai endpoint B2 trả **201 Created**, ví dụ minh họa với thời gian/sequence cố định:

```json
{
  "txnRef":"TX00000001",
  "orderId":"ORD20261102001",
  "amount":55000,
  "status":"PENDING",
  "expireAt":"2026-11-02T09:15:00+07:00",
  "qrString":"00020101021226230010A0000007750105MC0015204549953037045405550005802VN5908SHOP ABC6005HANOI62530522012611020915TX000000010818Thanh toan ao thun0901M6304C9E9"
}
```

Gateway thêm header `X-Signature` ký toàn bộ response JSON. Merchant phải verify trước
khi cập nhật đơn. Lỗi có body parse được và merchant đã đăng ký cũng được ký; lỗi
JSON chưa parse được hoặc merchant không tồn tại không thể ký bằng secret của merchant.
Merchant coi response thiếu/sai chữ ký là không đáng tin và không cập nhật đơn.

| Tình huống B2 | HTTP / mã hoặc hành vi |
|---|---|
| Đúng request | 201, PENDING, txnRef/QR/hạn thanh toán |
| Cùng merchantId/orderId và amount | 201, trả nguyên giao dịch cũ, không đổi QR/hạn |
| Cùng merchantId/orderId, khác amount | 409 ORDER_CONFLICT |
| Merchant chưa đăng ký | 404 MERCHANT_NOT_FOUND tại Gateway |
| Signature thiếu/sai | 401 INVALID_SIGNATURE |
| Amount/description sai | 400 INVALID_AMOUNT / INVALID_DESCRIPTION |
| expireMinutes thiếu/sai kiểu/ngoài 1–60 | 400 INVALID_EXPIRE_MINUTES |
| Identifier hoặc trường lạ không hợp lệ | 400 INVALID_REQUEST |
| Không tìm thấy đơn phía Merchant | 404 ORDER_NOT_FOUND |
| Mất kết nối/không biết Gateway đã commit chưa | Merchant trả 502 GATEWAY_UNAVAILABLE |
| Response Gateway hỏng, thiếu/sai HMAC hoặc không khớp đơn | Merchant trả 502 INVALID_GATEWAY_RESPONSE |

Nếu Gateway đã commit nhưng Merchant mất response hoặc lưu đơn thất bại, gọi lại
**endpoint payments với cùng orderId**. Gateway trả txnRef cũ và Merchant cập nhật lại
trong transaction ngắn. Không tạo lại B1 để retry B2. Thay description/expireMinutes
của request replay không cập nhật giao dịch cũ. B2 chưa chạy job hết hạn; replay cũng
không gia hạn hoặc tự tạo giao dịch mới.

## API B3 — Wallet quét QR

`POST /api/wallet/scan`

```json
{"userId":"U001","qrString":"<chuỗi qrString trả về từ B2>"}
```

PowerShell, tiếp theo biến `$payment` ở ví dụ B2:

```powershell
$scan = Invoke-RestMethod -Method Post -Uri http://localhost:8080/api/wallet/scan `
  -ContentType application/json `
  -Body (@{userId='U001'; qrString=$payment.qrString} | ConvertTo-Json)
$scan
```

200 OK, ví dụ (hạn thanh toán thực tế lấy từ Gateway):

```json
{
  "txnRef":"TX00000001",
  "merchantName":"SHOP ABC",
  "merchantCity":"HANOI",
  "amount":55000,
  "currency":"VND",
  "description":"Thanh toan ao thun",
  "expireAt":"2026-11-12T09:15:00+07:00"
}
```

Wallet parse TLV/required tags và CRC trước khi gọi HTTP; lấy txnRef sau prefix 01
và 10 ký tự thời hạn của 62/05. Mọi trường hiển thị lấy từ Gateway, chỉ amount QR
được dùng để đối chiếu. Hạn trong QR không quyết định hết hạn. B3 không ghi DB.

| Trường hợp | HTTP / mã |
|---|---|
| TLV hỏng, thiếu/duplicate tag, sai 62/05, QR trống | 422 INVALID_QR |
| CRC khác CRC tính lại | 422 CRC_MISMATCH |
| Amount QR khác amount Gateway | 422 AMOUNT_MISMATCH |
| Giao dịch không có | 404 TXN_NOT_FOUND |
| Trạng thái terminal hoặc PROCESSING | 422 TXN_NOT_PAYABLE |
| EXPIRED hoặc PENDING đến/quá expireAt của Gateway | 422 TXN_EXPIRED |
| HTTP Gateway mất kết nối/5xx | 502 GATEWAY_UNAVAILABLE |
| Response Gateway hỏng/sai chữ ký/không khớp txnRef | 502 INVALID_GATEWAY_RESPONSE |

Lỗi đều có errorCode/message/timestamp. Chọn TXN_EXPIRED theo bảng mã và thiết kế
đã duyệt vì mô tả B3 có chỗ dùng TXN_NOT_PAYABLE cho cả hết hạn. userId chỉ là ngữ
cảnh bắt buộc; chưa xác thực người dùng, PIN hay nạp wallet_user ở B3.

### GET Gateway dùng chung cho Wallet và Merchant

`GET /api/gateway/payments/{txnRef}` không cho truy cập ẩn danh:

- **Wallet:** `Authorization: Bearer <read-token>`. Chỉ trả txnRef, merchantName,
  merchantCity, amount, currency, description, status, expireAt; không orderId,
  bankRef hoặc tài khoản. Token riêng của Wallet, không phải secret Merchant.
- **Merchant:** cặp `X-Merchant-Id` / `X-Signature` bắt buộc. Chuỗi ký GET:
  `merchantId=MC001&txnRef=TX00000001`. Chỉ đọc giao dịch thuộc merchant đã xác
  thực; response gồm các trường B7 theo đề, bỏ bankRef/paidAt nếu chưa có.
- Có bất kỳ header Merchant nào là bắt buộc đi nhánh HMAC. Thiếu/sai/duplicate
  header hoặc trộn với Authorization đều bị từ chối, không fallback sang Wallet.
- Response sau xác thực (kể cả 404) có `X-Signature` HMAC trên JSON và
  `Cache-Control: no-store`: dùng secret Merchant hoặc read-token Wallet tương ứng.
  Wallet verify signature và txnRef trước khi dùng response.

Cấu hình `QR_WALLET_READ_TOKEN` ở hai phía (mẫu dev local mặc định
`wallet-read-token-dev-only`); `QR_GATEWAY_BASE_URL` dùng như B2. Token không nhận từ
body scan và không nằm trong QR. B7 mới có nền tảng GET có phân quyền; chưa triển
khai workflow Merchant tra cứu đơn hay job hết hạn.

## API B4–B5 — Thanh toán

`POST /api/wallet/payments`:

```json
{
  "userId":"U001",
  "txnRef":"TX00000001",
  "amount":55000,
  "pin":"123456",
  "requestId":"4f1c2a9e-7b3d-4c55-9a10-0e2b6f8d1a77"
}
```

Wallet kiểm BCrypt, lấy payerAccount từ user, rồi gọi HTTP
`POST /api/gateway/payments/{txnRef}/confirm` với body đúng hợp đồng:

```json
{"payerAccount":"1000001","amount":55000,"requestId":"4f1c2a9e-7b3d-4c55-9a10-0e2b6f8d1a77"}
```

Confirm bắt buộc `X-Wallet-Signature`: HMAC-SHA256 của các trường body cộng txnRef
trong path, sort key và nối bằng &. Dùng secret riêng `QR_WALLET_PAYMENT_SECRET`
(mẫu local `wallet-payment-secret-dev-only`), **không dùng token đọc B3**. PIN không
gửi sang Gateway; client không thể thêm payerAccount vào request Wallet.

200 SUCCESS (ví dụ):

```json
{
  "txnRef":"TX00000001","status":"SUCCESS","amount":55000,
  "bankRef":"BK20261112000001","paidAt":"2026-11-12T09:03:12+07:00",
  "message":"Thanh toán thành công"
}
```

Thiếu tiền trả 200 với status FAILED, amount, errorCode INSUFFICIENT_FUNDS và
message; không bankRef/paidAt, không thay đổi số dư hoặc tạo ledger.

| Trường hợp | HTTP / mã |
|---|---|
| PIN sai | 401 INVALID_PIN |
| Lần sai thứ ba và trong 15 phút khóa | 423 PIN_LOCKED |
| User không có | 404 WALLET_USER_NOT_FOUND |
| Confirm thiếu/sai credential thanh toán | 401 INVALID_WALLET_CREDENTIALS |
| Giao dịch không tồn tại | 404 TXN_NOT_FOUND |
| Không còn PENDING | 422 TXN_NOT_PAYABLE |
| PENDING đến/quá hạn, hoặc đã EXPIRED | 422 TXN_EXPIRED, lưu EXPIRED |
| Amount khác DB | 422 AMOUNT_MISMATCH |
| Account nguồn/đích không có | 404 ACCOUNT_NOT_FOUND, payment FAILED |
| Account nguồn trùng đích | 422 INVALID_TRANSFER, payment giữ PENDING |
| Cùng UUID nhưng khác txnRef/account/amount | 409 IDEMPOTENCY_CONFLICT |
| Wallet timeout/kết nối/5xx/response không xác thực | 502 PAYMENT_RESULT_UNKNOWN |

Với kết quả UNKNOWN, **giữ nguyên requestId/txnRef/amount để retry**; không tạo UUID
mới và không kết luận FAILED. Gateway lưu status HTTP và body response cùng transaction
tiền; replay không tạo bankRef/ledger mới. Không có auto retry vô hạn trong Wallet.

Seed U001/PIN 123456/số dư 1.000.000; U002/PIN mẫu 123456/số dư 10.000;
merchant account 9000001/số dư 0. PIN chỉ lưu BCrypt strength 10. Seed chỉ thêm khi
thiếu, không reset số dư hoặc lock khi restart. PIN đúng reset counter; mỗi lần xác
thực PIN sai được đếm, kể cả requestId được lặp, để không cho thử PIN vô hạn bằng
cách tái sử dụng UUID. Khóa/bộ đếm commit trước khi trả lỗi và trước HTTP Gateway.

Gateway khóa payment_txn và hai account theo thứ tự account_no cố định. Bank dùng
propagation MANDATORY, cùng transaction JPA; debit, credit, hai ledger và cache kết
quả hoặc commit tất cả, hoặc rollback tất cả. Lỗi hệ thống không được đổi thành
thành công/thiếu tiền. B5 commit thêm outbox; worker B6 cập nhật Merchant sau commit.

## API B6 — IPN tới Merchant

Sau khi B5 commit SUCCESS hoặc FAILED, Gateway tự gửi
`POST {merchant.ipn_url}` bằng RestClient. URL mẫu MC001 là
`http://localhost:8080/api/merchant/ipn`. Nếu đổi cổng, cập nhật registry trước khi
thanh toán; URL và payload đã tạo trong outbox được giữ cố định khi retry.

```mermaid
flowchart LR
    B[B5: kết quả + tiền + idempotency] -->|cùng commit| O[(ipn_outbox READY)]
    O -->|transaction claim ngắn| W[IPN Worker]
    W -->|HTTP POST + HMAC, ngoài transaction| M[Merchant: khóa đơn và commit]
    M -->|ACK 00 hoặc 02| W
    W -->|transaction ngắn| L[(ipn_log + DELIVERED hoặc lịch retry)]
```

Ví dụ body SUCCESS (signature phải được tính từ toàn bộ trường còn lại):

```json
{
  "txnRef":"TX00000001","orderId":"ORD20261102001","amount":55000,
  "status":"SUCCESS","bankRef":"BK20261102000001",
  "paidAt":"2026-11-02T09:03:12+07:00","signature":"<hex HMAC-SHA256>"
}
```

FAILED chỉ có txnRef/orderId/amount/status/signature; bỏ bankRef/paidAt.
HMAC dùng secret Merchant, UTF-8, bỏ signature và giá trị null, sort key tăng dần,
nối `key=value` bằng `&`, giữ nguyên format paidAt và không URL-encode, như B2.
Merchant dùng secret cấu hình cục bộ; không nhận secret hoặc merchantId tùy ý từ IPN.

| HTTP 200 / code | Ý nghĩa |
|---|---|
| `97` / Invalid signature | Sai hoặc thiếu HMAC, ưu tiên trước kiểm đơn |
| `01` / Order not found | Đơn không thuộc Merchant hoặc txnRef không khớp đơn |
| `04` / Invalid amount | Amount không khớp đơn |
| `02` / Order already confirmed | Đơn đã ra khỏi WAITING_PAYMENT; không thay đổi |
| `00` / Confirm success | SUCCESS → PAID; FAILED → PAYMENT_FAILED |

Response đúng hai trường, ví dụ `{"code":"00","message":"Confirm success"}`.
Merchant khóa đơn trong transaction; replay trả 02 và IPN mâu thuẫn không đảo PAID.
JSON hỏng hoặc payload có chữ ký đúng nhưng status/paidAt/bankRef sai cấu trúc trả
400 INVALID_REQUEST theo error envelope kỹ thuật hiện có.

Worker poll mặc định 1s, gửi tối đa **3 lần tổng cộng**: lần đầu ngay khi claim,
lần hai sau 10s kể từ kết thúc lần trước, lần ba sau thêm 30s. Không gửi lần thứ tư.
HTTP timeout/connect error, non-2xx, JSON lỗi hoặc code khác 00/02 đều retry;
00/02 trên HTTP 2xx kết thúc. RestClient connect 3s/read 5s, không follow redirect.
Mỗi attempt được reserve cùng log trước HTTP; lease mặc định 60s, token ngăn ACK cũ
ghi đè công việc đã thu hồi. Hết budget giữ EXHAUSTED, không hoàn tiền/chuyển lại tiền.

Cấu hình tùy chọn (giá trị mặc định nằm trong `IpnProperties`):

```yaml
app:
  gateway:
    ipn:
      enabled: true
      poll-millis: 1000
      lease: PT1M
      second-delay: PT10S
      third-delay: PT30S
```

Lease tối thiểu 15s. Test mặc định tắt scheduler để các ca B1–B5 giữ fixture cũ;
test B6 gọi worker thật với Clock điều khiển được, và IT bật scheduler qua restart.
Không thêm broker hoặc API kích hoạt worker. Chạy B1 → B2 → Wallet payments như ví dụ
trên là đủ để phát sinh IPN; xem outbox/log/orders qua H2 console dev.

## Kiểm thử

```powershell
.\mvnw.cmd test
```

```bash
sh mvnw test
```

Test tự chọn profile `test`, dùng H2 memory riêng và fixed Clock khi kiểm thời gian.
Không bọc test HTTP trong transaction. Test B2 có Gateway HTTP thật và proxy thử lỗi
để kiểm mất response sau commit, lỗi lưu Merchant, response hỏng và concurrency.
Kiểm tra qr-parser ngoài được tách thành
`QrParserCompatibilityIT`, phải khởi động project gốc để chạy, không âm thầm skip.

Kiểm tra Type04 bằng project gốc trong terminal khác, từ thư mục qr-parser chứa pom.xml:

```powershell
.\mvnw.cmd package
java -jar target/qr-parser-0.0.1-SNAPSHOT.jar --server.port=8081
```

Tại qr-payment-flow, chạy **toàn bộ B1–B6, Type04 và restart thật**:

```powershell
.\mvnw.cmd "-Dtest=*Test,*IT" "-Dqr.parser.url=http://localhost:8081" test package
```

Kết quả thực tế ngày 2026-11-13: **244 PASS, 0 FAIL, 0 errors, 0 skipped**, gồm
219 test B1–B5 và 25 test B6 (24 integration test, một IT restart ba process).
`mvnw test` mặc định chọn 241 test nội bộ; full command trên bao gồm ba lớp IT.
`qr-parser`
gốc trả HTTP 200, `status=VALID`, CRC VALID cho
QR thực sự sinh bởi Gateway với `type="04"`. JAR đóng gói thành công. Đây chưa phải
toàn bộ 12 kịch bản end-to-end B1–B8. Xem [báo cáo B6](docs/phase6-report.md).

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

## Phạm vi còn lại

Dừng tại B6. Chưa có workflow Merchant tra cứu B7, job hết hạn, đối soát B8 hoặc
hoàn tiền. PaymentCompleted chỉ phục vụ quan sát nội bộ; tính bền vững IPN dựa trên
outbox trong transaction B5. Các giới hạn giao nhận, lease và nâng cấp dữ liệu cũ
được ghi rõ trong [báo cáo B6](docs/phase6-report.md).
