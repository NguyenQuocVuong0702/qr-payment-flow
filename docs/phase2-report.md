# Báo cáo giai đoạn 2 — Gateway tạo giao dịch và QR động

Ngày kiểm chứng: 2026-11-09, múi giờ +07:00. Nguồn yêu cầu là
[đề chính thức](../bai-tap-luong-thanh-toan-qr.md); thiết kế chi tiết nằm ở
[architecture.md](architecture.md). Dừng ở B2.

## 1. Chức năng đã triển khai

- Registry `merchant` và seed MC001 đầy đủ name/city/MCC/secret/ipn_url/account_no.
  Seed chỉ insert khi thiếu, không ghi đè dữ liệu khi restart. Account_no là metadata;
  không tạo hoặc xử lý tài khoản Bank.
- Entity/repository/schema `payment_txn`, FK merchant và unique `(merchant_id, order_id)`;
  amount/description/status/expiry có constraint, txnRef dùng sequence DB cố định.
- `POST /api/gateway/payments`, validation, merchant lookup, HMAC request,
  `PENDING`, expireAt, sinh QR và trả 201 đúng sáu trường đề bài.
- HMAC-SHA256 UTF-8, lowercase hex, canonicalization sort key, bỏ signature,
  không URL-encode; so byte bằng `MessageDigest.isEqual`. Response được ký bằng
  `X-Signature`, Merchant verify trước khi dùng dữ liệu. Error envelope cũng ký khi
  request đã parse được merchantId đăng ký.
- TLV đầy đủ 00/01/26(00,01)/52/53/54/58/59/60/62(05,08,09)/63; CRC-16/CCITT-FALSE.
- Replay giữ nguyên txnRef/QR/hạn cũ, khác amount trả 409 ORDER_CONFLICT. Các
  request tranh chấp insert rollback rồi đọc winner trong transaction mới.
- Endpoint Merchant mới chỉ nhận expireMinutes. Dùng RestClient qua HTTP, không
  import code Gateway. Amount/description/merchantId lấy từ đơn đã lưu. Đơn được
  attach txnRef và chuyển WAITING_PAYMENT trong transaction riêng, có khóa dòng.
- Phục hồi bằng retry payments cùng orderId khi Gateway đã commit nhưng Merchant
  mất response, response không đáng tin hoặc lưu đơn thất bại. Không tạo lại B1.
- B1 giữ nguyên API/hành vi/validation 19 ký tự và nguyên source hai lớp test B1.

## 2. Luồng Merchant → Gateway → Merchant

```mermaid
sequenceDiagram
    participant C as Client
    participant M as Merchant
    participant G as Gateway
    C->>M: POST /api/merchant/orders
    M-->>C: 201 CREATED + orderId (B1 không đổi)
    C->>M: POST /api/merchant/orders/{orderId}/payments
    M->>M: Đọc snapshot từ DB; kết thúc transaction
    M->>G: HTTP POST /api/gateway/payments + signature
    G->>G: Transaction: verify, tìm/tạo payment_txn, commit
    G-->>M: 201 PENDING + txnRef + QR + X-Signature
    M->>M: Verify response, so orderId/amount/status
    M->>M: Transaction khóa orders, lưu txnRef + WAITING_PAYMENT
    M-->>C: 201 với nguyên dữ liệu Gateway
    Note over C,G: Nếu kết quả chưa nhận/lưu được: retry payments cùng orderId
```

`MerchantPaymentService` không mở transaction; propagation NEVER còn ngăn caller
bao một transaction bên ngoài. `MerchantPaymentStore.load` và `attach` là hai
transaction ngắn tách biệt. Không có transaction database giữ mở trong lúc HTTP.

## 3. API và ví dụ

| API | Input | Output |
|---|---|---|
| POST /api/merchant/orders | amount, description như B1 | 201 CREATED, không gọi Gateway |
| POST /api/merchant/orders/{orderId}/payments | chỉ expireMinutes | 201 cùng sáu trường của Gateway, Merchant lưu WAITING_PAYMENT |
| POST /api/gateway/payments | merchantId, orderId, amount, description, expireMinutes, signature | 201 PENDING + QR; header X-Signature |

Ví dụ kích hoạt Merchant sau B1:

```http
POST /api/merchant/orders/ORD20261102001/payments
Content-Type: application/json

{"expireMinutes":15}
```

Merchant tự xây request Gateway từ đơn đã lưu:

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

Canonical string chính xác của fixture trên:

```text
amount=55000&description=Thanh toan ao thun&expireMinutes=15&merchantId=MC001&orderId=ORD20261102001
```

Ví dụ response minh họa với thời gian cố định 2026-11-02 09:00 +07 và sequence đầu:

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

Đây là fixture cố định; txnRef và expireAt khi gọi thực tế phụ thuộc sequence/Clock.
Cách chạy đầy đủ và bảng HTTP/mã lỗi ở [README](../README.md#api-b2-và-luồng-merchant--gateway--merchant),
các request có thể chạy ở [requests/b2.http](../requests/b2.http).

## 4. Các quyết định kỹ thuật

- Không thay B1 thành thao tác tự tạo QR: endpoint payments mới tách biệt, giữ B1 CREATED.
  Body chỉ expireMinutes; trường lạ bị reject. Không tin amount/description phía client.
- Unique business key theo merchantId/orderId, không khóa cả registry merchant để
  serialize mọi đơn. Sequence DB cùng PK tránh trùng txnRef; unique constraint bảo vệ
  ngay cả khi nhiều request đều chưa nhìn thấy row lúc đọc.
- Không catch unique violation trong transaction đã rollback-only. Bean điều phối
  bắt lỗi sau khi transaction insert đã rollback rồi gọi read transaction riêng.
  Khác amount vẫn được so sánh trong đường phục hồi race.
- Merchant khóa order lúc attach; WAITING_PAYMENT cùng txnRef là no-op. Lỗi trước
  commit rollback hoàn toàn; không để txnRef tồn tại nhưng status vẫn CREATED.
- expireAt lưu độ chính xác giây, API +07:00; tag62/05 format yyMMddHHmm theo đề.
  DTO HTTP Merchant tắt việc Jackson tự đổi OffsetDateTime về UTC để giữ nguyên response.
- `TX` + 8 số, sequence tối đa 99999999 NO CYCLE để giữ 62/05 trong giới hạn Type04.
  Sequence có thể nhảy số khi restart/rollback. Không quay vòng hoặc tái sử dụng ID.
- HMAC ký response bằng header để không thay JSON của đề. Không tin lỗi unsigned
  (JSON chưa parse được/merchant không tồn tại). HMAC B2 chỉ hỗ trợ scalar; phần array
  của B8 chưa triển khai. Không log secret/signature; toString request/config được redact.
- Đề chưa đặt mã expireMinutes hoặc lỗi endpoint Merchant mới: dùng 400
  INVALID_EXPIRE_MINUTES, 404 ORDER_NOT_FOUND, 502 GATEWAY_UNAVAILABLE và
  INVALID_GATEWAY_RESPONSE, giữ envelope chung và các mã nghiệp vụ gốc.
- Schema B2 chỉ bổ sung bảng/sequence; không đổi bảng orders của B1. Không thêm
  dependency, broker, distributed transaction, Bank processing, PIN hoặc IPN.

## 5. Kết quả kiểm thử thực tế

Test chạy thật trên Java 21 bằng Maven Wrapper, không có test placeholder/disabled.
Lệnh bản cuối (parser gốc được chạy riêng ở cổng tạm):

```powershell
$env:MAVEN_USER_HOME = Join-Path $PWD '.build\maven-user-home'
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.build\repository" `
  "-Dtest=*Test,*IT" "-Dqr.parser.url=http://127.0.0.1:<cong-parser>" test package
```

| Lớp test | Số PASS | FAIL / error / skipped |
|---|---:|---|
| ApplicationContextTest (B1) | 1 | 0 / 0 / 0 |
| OrderApiIntegrationTest (B1, giữ nguyên) | 65 | 0 / 0 / 0 |
| CryptoAndQrTest | 10 | 0 / 0 / 0 |
| GatewayPaymentApiIntegrationTest | 29 | 0 / 0 / 0 |
| MerchantPaymentHttpIntegrationTest | 12 | 0 / 0 / 0 |
| QrParserCompatibilityIT | 1 | 0 / 0 / 0 |
| **Tổng** | **118** | **0 / 0 / 0** |

Maven BUILD SUCCESS, tạo executable JAR `target/qr-payment-flow-0.0.1-SNAPSHOT.jar`.
`test package` không chạy trùng test: Surefire bỏ lượt lifecycle sau vì cùng cấu hình
đã được thực hiện. `mvnw test` thông thường chạy 117 test nội bộ; IT ngoài được chạy
tường minh bằng lệnh trên, không dùng điều kiện skip để che thiếu project parser.

Những ca quan trọng đã đạt:

- Valid create/persistence, MC001 seed, đúng sáu trường, PENDING và hạn theo Clock;
  amount/description/expiry ở biên và sai kiểu, JSON trường lạ, missing signature,
  signature sai hoặc sửa amount sau ký, merchant không tồn tại.
- Canonical string/HMAC fixture kiểm tra độc lập; CRC `123456789` → `29B1`;
  QR đúng thứ tự và đủ giá trị tag/template, CRC fixture `C9E9`, TLV length 2 số.
- Replay đúng nguyên response; đổi description/hạn vẫn giữ dữ liệu cũ; khác amount
  409; 8 request cùng lúc chỉ 1 row/cùng response; 2 request khác amount đồng thời
  cho một order tạo đúng một 201 và một 409; constraint cũng được thử bằng SQL trực tiếp.
- Merchant gọi HTTP thật qua proxy tới embedded Gateway; assert không có transaction
  tại thời điểm gọi HTTP. Hai request Merchant đồng thời cùng order chỉ attach một txn.
- Drop response sau khi Gateway commit; throw lỗi flush Merchant làm rollback;
  signature response sai; JSON hỏng/array/null/nested. Tất cả không cập nhật sai đơn
  và retry cùng orderId khôi phục mà không tạo payment thứ hai.
- Response Merchant bằng nguyên response Gateway, bao gồm offset expireAt +07:00.

### Kiểm chứng bằng qr-parser gốc

Đã có source ở `../qr-parser/qr-parser`, HEAD `7518a7289b547437b1a1dc156a8db26a991d1f65`.
Đọc source/README để xác định endpoint `/api/qr/analyze` và body `{qr,type}`.
Để không ghi ngoài workspace, sao chép nguyên pom/main source vào
`.build/original-qr-parser`, đối chiếu SHA-256 từng file rồi build main application
(không sửa source; không tuyên bố đã chạy lại bộ unit test riêng của parser).

`QrParserCompatibilityIT` tạo giao dịch qua API Gateway thật của ứng dụng test,
lấy qrString vừa sinh rồi gọi **HTTP API của project parser gốc** với `type="04"`.
Kết quả: **HTTP 200, status VALID, errors rỗng, crcResult.status VALID**.
Không dùng parser tự viết để kết luận tương thích.

### Smoke test JAR và nâng cấp file H2 B1

Chạy JAR trên một **bản sao** H2 file B1 chứa 2 đơn, không sửa file dev hay file gốc.
Ứng dụng tự bổ sung schema B2 và seed MC001. Qua HTTP thật, tạo một đơn B1, kích
hoạt B2 rồi replay (đổi expireMinutes từ 15 xuống 1) nhận cùng response/hạn cũ.
Sau khi dừng tiến trình đột ngột, SQL xác nhận:

- 2 đơn B1 cũ còn nguyên; tổng cộng 3 đơn.
- Chỉ có 1 payment_txn; đơn mới WAITING_PAYMENT và txn_ref khớp.
- Response giữ +07:00, CRC/QR/hạn không thay đổi khi replay.

Bằng chứng cục bộ (build artifacts được ignore):
[Maven log](../.build/b2-final-verify.log), [Surefire XML](../target/surefire-reports/),
[log parser HTTP](../.build/qr-parser-http.log),
[checksum source parser](../.build/qr-parser-source-checksums.json),
[smoke result](../.build/b2-upgrade-result.json).

## 6. Git và giới hạn

Workspace ban đầu chưa có repository. Đã khởi tạo Git và commit B1 **trước khi sửa B2**:
`a999435` — `feat: preserve reviewed phase 1 merchant order creation`.
B2 có commit riêng sau khi test đạt yêu cầu, không rewrite lịch sử.

- Dừng B2; chưa triển khai B3–B8, Bank/account/ledger, scan, PIN, IPN, query, expiry
  job, đối soát hoặc refund. Các cột/enum cho bước sau không đồng nghĩa đã có nghiệp vụ.
- Trong 12 kịch bản end-to-end của đề, đã kiểm chứng #2; các kịch bản còn lại chưa
  có điều kiện thực hiện đầy đủ. Không tuyên bố full flow B1–B8 PASS.
- Type04 được kiểm với một QR mới sinh ở ca tích hợp; không phải chứng nhận toàn bộ
  EMV hoặc mọi QR/registry bên ngoài. Chưa test nhiều JVM, benchmark hoặc production DB.
- Retry Merchant là client gọi lại cùng orderId; chưa có job khôi phục tự động hoặc
  tạo lại QR hết hạn. B1 vẫn không có client request key theo hợp đồng đã duyệt.
- H2 và secret mẫu dành cho bài tập; chưa bổ sung công nghệ hoặc xác thực ngoài phạm vi.
