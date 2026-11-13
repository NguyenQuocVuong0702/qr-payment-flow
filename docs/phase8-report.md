# Báo cáo B8 — đối soát giao dịch cuối ngày

Ngày kiểm chứng **2026-11-13**, múi giờ +07:00. Bắt đầu từ Git sạch **b2e8665**
(B7), bảo toàn lịch sử và nghiệp vụ/API B1–B7. Nguồn ưu tiên:
[đề chính thức](../bai-tap-luong-thanh-toan-qr.md) và yêu cầu B8 hiện tại.
Dừng sau B8; không có refund, broker, scheduler đối soát hoặc tự sửa đơn.

## Luồng đối soát

```mermaid
sequenceDiagram
    participant C as Client
    participant M as Merchant
    participant G as Gateway
    C->>M: POST /api/merchant/reconcile {date}
    M->>G: HTTP GET /api/gateway/reconciliation?merchantId&date + HMAC
    G->>G: Xác thực Merchant; transaction chỉ đọc SUCCESS theo paidAt
    G-->>M: Summary + transactions + X-Signature, no-store
    M->>M: Verify HMAC, schema, merchant/date, IDs, amount, count/total
    M->>M: Transaction chỉ đọc PAID và binding local; kết thúc
    M->>M: Map theo txnRef; phân loại matched/missing/amountMismatch
    M-->>C: Báo cáo bảy trường, không thay đổi dữ liệu
```

GatewayPaymentClient/RestClient, BoundedHttpRequests, PaymentLookupAccess và
LookupResponseSigningAdvice được tái sử dụng. Merchant không import Gateway
repository/service/domain/DTO. MerchantReconciliationService dùng NEVER; transaction
snapshot local nằm trong bean riêng, không có transaction hay khóa DB xuyên HTTP.
Gateway tính count và total từ cùng một danh sách query, không dùng hai query tổng
hợp ở hai thời điểm có thể khác nhau.

## API và ví dụ

Gateway:

```http
GET /api/gateway/reconciliation?merchantId=MC001&date=2026-11-02
X-Merchant-Id: MC001
X-Signature: 3e01f48ad5777cf5f12725406029a2ae37936d4e070afb30e12e3d692fe5911f
```

Chữ ký mẫu đúng cho canonical `date=2026-11-02&merchantId=MC001` và secret-mc001.
Header Merchant phải khớp query; một header mỗi loại, không mixed Authorization,
không credential Wallet. Query phải có đúng một merchantId/date, không trường lạ.
Sai auth/binding header trả 401 INVALID_SIGNATURE như thiết kế B7. Không thể dùng
key MC001 ký query MC002 rồi đọc dữ liệu MC002.

Response 200, đúng năm trường theo đề; ví dụ:

```json
{
  "merchantId":"MC001","date":"2026-11-02","successCount":2,"successAmount":110000,
  "transactions":[
    {"txnRef":"TX00000001","orderId":"ORD20261102001","amount":55000,
     "status":"SUCCESS","bankRef":"BK20261102000001","paidAt":"2026-11-02T09:03:12+07:00"},
    {"txnRef":"TX00000003","orderId":"ORD20261102003","amount":55000,
     "status":"SUCCESS","bankRef":"BK20261102000002","paidAt":"2026-11-02T10:20:00+07:00"}
  ]
}
```

Mỗi transaction đúng sáu trường, không payerAccount/account_no, secret, QR hoặc mô
tả không cần thiết. Giao dịch sort theo txnRef. Response thành công và lỗi sau xác
thực được ký trong X-Signature, no-store, giữ cơ chế B7.

Merchant:

```http
POST /api/merchant/reconcile
Content-Type: application/json

{"date":"2026-11-02"}
```

Ví dụ một giao dịch nhận đủ IPN và một giao dịch mất IPN:

```json
{
  "date":"2026-11-02","matched":1,
  "missingInMerchant":[{"txnRef":"TX00000003","orderId":"ORD20261102003","amount":55000}],
  "missingInGateway":[],"amountMismatch":[],"gatewayTotal":110000,"merchantTotal":55000
}
```

Không thêm trường ngoài bảy trường đề bài. missingInGateway có cùng shape
{txnRef,orderId,amount}, amount lấy từ Merchant. Đề chưa định nghĩa phần tử
amountMismatch: chọn `{txnRef,orderId,gatewayAmount,merchantAmount}` để thấy cả hai số.

| Trường hợp lỗi | HTTP / mã |
|---|---|
| Ngày thiếu/null, sai yyyy-MM-dd hoặc ngày lịch không tồn tại | 400 INVALID_DATE |
| JSON sai kiểu/trường lạ/duplicate/trailing; query thiếu/thừa/trùng | 400 INVALID_REQUEST |
| HMAC/credential/header-query sai tại Gateway | 401 INVALID_SIGNATURE |
| Gateway không phản hồi, timeout headers/body hoặc 5xx | 502 GATEWAY_UNAVAILABLE tại Merchant |
| Response sai HMAC/schema/định danh/tổng hoặc trùng giao dịch | 502 INVALID_GATEWAY_RESPONSE |
| Local PAID thiếu txnRef/paidAt hợp lệ | 409 INVALID_MERCHANT_DATA |

Mã kỹ thuật ngày dùng INVALID_DATE vì đề chưa đặt mã B8 cho trường này; giữ envelope
errorCode/message/timestamp và không sửa mã cũ. Ngày sai trên Gateway được kiểm sau
auth nên lỗi có chữ ký nếu Merchant đã xác thực. Khi Gateway lỗi, không suy đoán
báo cáo rỗng hoặc matched từ dữ liệu local.

## Ngày, HMAC và kiểm tra toàn vẹn

Ngày theo **paidAt, Asia/Ho_Chi_Minh**, khoảng nửa mở [đầu ngày, đầu ngày kế tiếp).
Ngày 2026-11-13 tương ứng UTC [2026-11-12T17:00:00Z, 2026-11-13T17:00:00Z).
Không lấy createdAt, không CAST timestamp sang DATE theo timezone DB/JVM. Chấp nhận
timestamp offset khác nếu cùng instant thuộc ngày Việt Nam. BusinessDay dùng chung
để tránh lệch biên giữa Gateway và Merchant; tiền/count/total dùng long.

HmacSigner giữ nguyên scalar B2: bỏ signature/null ở root, sort tên key, nối key=value
bằng &, UTF-8, HMAC-SHA256 lowercase hex và verify constant-time. B8 bổ sung value
array/object là JSON compact, sort key object đệ quy, giữ thứ tự array và null bên
trong container. Không URL-encode hoặc chuẩn hóa timestamp trước khi verify.

Vector độc lập bằng Python json.dumps(sort_keys=True, separators=(',', ':'),
ensure_ascii=False) + hmac/hashlib: body Gateway hai transaction phía trên có HMAC
`c845704f632ff84a0a7212650a2dbd782ac7ccd0d21204270d372c5a628d5fdd` với secret-mc001.
ReconciliationHmacTest đối chiếu vector, kiểm sort đệ quy/Unicode/escaping và chứng
minh đổi thứ tự array làm đổi chữ ký. Các vector scalar cũ vẫn chạy PASS.

Client B8 dùng HTTP client B7: connect 3s/read 5s kể cả body, không redirect, giới
hạn response 4 MiB; vượt giới hạn trả lỗi thay vì cắt bớt transaction. Verify trên
JSON gốc trước deserialize; bắt buộc một response signature và đúng schema đầy đủ.
Merchant/date phải đúng request; amount nguyên/long trong miền B1, status SUCCESS,
paidAt trong ngày; txnRef/orderId hợp lệ, bankRef có giá trị. Không trùng txnRef,
orderId hoặc bankRef. Count phải bằng số dòng và total bằng tổng các amount hợp lệ.
Signature đúng không được bỏ qua các kiểm tra này.

Bindings local được đọc cùng snapshot PAID: nếu một txnRef đã gắn orderId khác hoặc
orderId đã gắn txnRef khác, từ chối INVALID_GATEWAY_RESPONSE, không tạo matched giả.
Amount khác ở hai bản ghi tương ứng là sai lệch đối soát hợp lệ, không phải lỗi HMAC.
Nếu đơn local chưa tồn tại hoặc CREATED chưa attach ref do mất kết quả B2, vẫn có
thể báo missingInMerchant. PAID không có paidAt không thể gán ngày an toàn nên
từ chối, kể cả khi không thể biết nó thuộc ngày nào; không âm thầm bỏ khỏi tổng.

## Thuật toán và các loại lệch

Gọi G là tập Gateway SUCCESS của ngày, M là tập Merchant PAID của ngày:

1. Sau xác thực G, đọc snapshot local trong một transaction chỉ đọc, tạo Map theo
   txnRef/orderId để kiểm binding và Map PAID của ngày. Cộng merchantTotal bằng long.
2. Duyệt mỗi dòng G một lần. Không có trong M → missingInMerchant; có trong M nhưng
   amount khác → amountMismatch; cùng amount/orderId → matched.
3. Duyệt M một lần, ref không có trong G → missingInGateway. Không có so sánh lồng
   hai danh sách. Chi phí so sánh O(n+m), bộ nhớ O(n+m), ngoài query/sort ở DB.

Các nhóm loại trừ nhau. `|G| = matched + |amountMismatch| + |missingInMerchant|`,
`|M| = matched + |amountMismatch| + |missingInGateway|`. Totals cộng độc lập toàn bộ
tập tương ứng, không chỉ matched; Math.addExact tránh wrap nếu vượt long.

| Fixture đã kiểm thử | matched | missing Merchant | missing Gateway | amount mismatch | Gateway total | Merchant total |
|---|---:|---:|---:|---:|---:|---:|
| Một SUCCESS, IPN nhận đủ | 1 | 0 | 0 | 0 | 55.000 | 55.000 |
| Hai SUCCESS, hai IPN | 2 | 0 | 0 | 0 | 110.000 | 110.000 |
| IPN 500 cả ba lần | 0 | 1 | 0 | 0 | 55.000 | 0 |
| Local PAID, Gateway PENDING | 0 | 0 | 1 | 0 | 0 | 55.000 |
| Cùng txnRef/orderId, 55.000 khác 56.000 | 0 | 0 | 0 | 1 | 55.000 | 56.000 |
| Tổng hợp bốn nhóm | 1 | 1 | 1 | 1 | 165.000 | 166.000 |
| Ngày rỗng | 0 | 0 | 0 | 0 | 0 | 0 |
| Sáu SUCCESS x 500.000.000 | 6 | 0 | 0 | 0 | 3.000.000.000 | 3.000.000.000 |

Missing/amountMismatch chỉ xét thành viên hai tập ngày. PAID có paidAt sai ngày so
với Gateway sẽ biểu hiện thiếu ở tập ngày tương ứng, không matched xuyên ngày.
SQL sửa trạng thái/amount chỉ là fixture test mô phỏng dữ liệu legacy bị lệch;
API sản phẩm không làm các thay đổi đó.

Đối soát không gọi Bank hoặc sync, không thêm ledger, sửa bankRef/idempotency/Order,
outbox hay audit IPN. Test snapshot trước/sau bao gồm toàn bộ các bảng này. Với
missingInMerchant, người gọi có thể POST sync B7 riêng, xác minh Gateway và cập nhật
có khóa; chạy lại đối soát sau đó. Kịch bản 11 kiểm chứng cả bước báo thiếu ban đầu
không sửa đơn và thao tác sync riêng sau đó đưa báo cáo về matched.

## Kiểm thử toàn hệ thống thực tế

Chuẩn hóa bằng Maven profile **full-suite**, không cần nhớ pattern tên test:

```powershell
# Terminal 1: trong project qr-parser gốc có pom.xml
.\mvnw.cmd package
java -jar target/qr-parser-0.0.1-SNAPSHOT.jar --server.port=8081

# Terminal 2: trong qr-payment-flow, JDK 21
.\mvnw.cmd -Pfull-suite "-Dqr.parser.url=http://localhost:8081" verify
```

Linux/macOS dùng `sh mvnw` với cùng tham số. Profile chọn cả *Test/*IT, chạy ba IT
đặc biệt, test đến đóng gói JAR; thiếu parser gốc thì Type04 FAIL, không skip.
Dependency ngoài: JDK 21, Maven Central cho lần tải đầu, project qr-parser gốc đang
chạy. Không có Bank ngoài, message broker hoặc database server ngoài H2.

Trong workspace hạn chế ghi, lần chạy thực tế đặt MAVEN_USER_HOME và maven.repo.local
ở `.build/`, khởi động JAR qr-parser gốc trên port ngẫu nhiên và gọi đúng:

```powershell
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.build\repository" -Pfull-suite `
  "-Dqr.parser.url=http://127.0.0.1:<port-parser>" verify
```

**377 PASS, 0 FAIL, 0 errors, 0 skipped; BUILD SUCCESS.** Maven mất **03:05**,
kết thúc **2026-11-13 11:09:48 +07:00**; JAR đóng gói thành công.

| Nhóm | Số test | Failure / error / skipped |
|---|---:|---|
| Toàn bộ B1–B7 đã có | 291 | 0 / 0 / 0 |
| ReconciliationHmacTest mới | 4 | 0 / 0 / 0 |
| ReconciliationHttpIntegrationTest mới | 82 | 0 / 0 / 0 |
| **Tổng** | **377** | **0 / 0 / 0** |

Đã chạy lại Type04 gốc (HTTP 200, status/CRC VALID, không dùng parser tự viết),
RestartPersistenceIT B5 (hai JVM thật/H2 file, 33.853s), IpnRestartIT B6 (ba JVM thật/
H2 file, 50.892s). New HTTP test mất 22.786s trong full run. Lần chạy riêng 86 test
mới cũng PASS (Maven 01:01); kiểm tra nền tảng 11 test trước đó PASS.

`mvnw test` mặc định chọn 374 test nội bộ, không thay việc chạy full-suite. Không
sửa/xóa/skip test B1–B7. Test B8 gọi HTTP thật từ test vào Merchant/Wallet, rồi các
thành phần gọi Gateway/IPN qua HTTP thật; stub chỉ kiểm soát lỗi/mất phản hồi hoặc
trả JSON bị sửa. Database H2 thật và Clock được dùng, không mock toàn bộ Gateway.

Các ca bổ sung gồm schema/duplicate txnRef/orderId/bankRef/count/total, wrong binding,
JSON sai kiểu/duplicate key/trailing, ngày/null/date invalid, auth/query isolation,
timeout trước/sau headers, redirect, payload quá lớn, snapshot không đổi, offset
timestamp khác, tổng long > int, transaction ở hai ngày và sát nửa đêm Việt Nam.
IPN được phép commit khi reconciliation HTTP đang chờ, chứng minh không giữ local
transaction xuyên HTTP. Các invariant số dư/ledger/idempotency B5 vẫn chạy đầy đủ.

Evidence cục bộ: `.build/b8-final-verify.log`, `.build/b8-qr-parser-http.log` và
`target/surefire-reports/TEST-*.xml`. Build/cache/log không đưa vào Git.

## Đủ 12 kịch bản bắt buộc

Tất cả các test dưới đây thực sự được chọn và PASS trong full-suite trên:

| # | Kịch bản đề | Test chứng minh chính | Trạng thái |
|---|---|---|---|
| 1 | B1→B8 U001, PAID, -55.000/+55.000, matched=1 | ReconciliationHttpIntegrationTest.scenario1FullB1ToB8FlowMatchesOneAndNeverMovesMoneyDuringReconciliation | PASS |
| 2 | QR B2 đưa vào project gốc Type04 VALID | QrParserCompatibilityIT.originalQrParserAcceptsActualGatewayQrAsType04 | PASS |
| 3 | Sửa một ký tự tên Merchant, giữ CRC | WalletScanHttpIntegrationTest.wrongCrcDoesNotCallGateway | PASS |
| 4 | Sửa amount thành 5000, tính lại CRC | WalletScanHttpIntegrationTest.changedAmountWithRecalculatedCrcIsRejected | PASS |
| 5 | U002 thiếu tiền, FAILED/PAYMENT_FAILED, số dư không đổi | ReconciliationHttpIntegrationTest.failedPendingAndExpiredTransactionsNeverEnterSuccessReport; IpnIntegrationTest.failedIpnOmitsUnassignedFieldsAndMarksPaymentFailed | PASS |
| 6 | Cùng requestId retry, chuyển tiền một lần | ConfirmationIntegrationTest.actualWalletHttpCanRecoverLostCommittedResultAndMerchantStaysWaiting; successCommitsBalancesDoubleEntryAndExactReplayOnce | PASS |
| 7 | Hai UUID khác nhau, cùng txnRef, chỉ một SUCCESS | ConfirmationIntegrationTest.simultaneousDifferentUuidsOnSameTxnHaveOneSuccess | PASS |
| 8 | Confirm hết hạn, TXN_EXPIRED, lưu EXPIRED | ConfirmationIntegrationTest.expiresAndCommitsExpiredStateAndStableErrorResponse; PaymentExpiryIntegrationTest | PASS |
| 9 | IPN 500,500,00, đúng ba log và PAID | IpnIntegrationTest.scenario9TwoHttp500ThenSuccessHasExactlyThreeLogs | PASS |
| 10 | Sai HMAC IPN trả 97, không đổi đơn | IpnIntegrationTest.scenario10InvalidSignatureReturns97AndNeverChangesOrder | PASS |
| 11 | IPN thất bại ba lần, đối soát missingInMerchant | ReconciliationHttpIntegrationTest.scenario11ThreeFailedIpnsAreReportedWithoutRepairAndSeparateB7SyncCanRepair | PASS |
| 12 | PIN sai ba lần, lần ba PIN_LOCKED | WalletPaymentHttpTest.thirdWrongPinLocksDurablyAndUnlocksAtExactly15Minutes | PASS |

## File thay đổi và cách demo

- Common: BusinessDay, HmacSigner mở rộng container nhưng giữ scalar.
- Gateway: ReconciliationResponse/Controller/Service; query SUCCESS của ngày trong
  PaymentTxnRepository; helper auth Merchant dùng lại trong PaymentLookupAccess.
- Merchant: ReconcileRequest/Result/Controller, GatewayReconciliation HTTP DTO và
  validation, phương thức B8 trong GatewayPaymentClient, MerchantReconciliationStore/
  Service, query local trong OrderRepository.
- schema.sql thêm hai index, không thêm bảng hoặc sửa bản ghi tài chính.
- pom.xml thêm full-suite, cập nhật mô tả; hai lớp test B8 mới. Không đổi API,
  BankTransferService, ConfirmationTransaction hoặc các test B1–B7.
- README, architecture, báo cáo này, requests/b8.http và requests/full-flow.http.

File full-flow.http có named response variables cho VS Code REST Client, nối tự
động orderId/QR/txnRef. Đặt ngày Việt Nam đúng lần chạy, UUID mới cho mua mới;
chờ worker B6 khoảng 1s, GET trạng thái rồi sync B7 riêng nếu cần, cuối cùng đối
soát. Nếu DB đã có nhiều demo cùng ngày, matched có thể lớn hơn 1. Không thao tác
GUI extension trong lượt kiểm chứng; các API tương ứng đã chạy qua HTTP trong test.

## Giới hạn và rủi ro còn lại

- Đã kiểm chứng H2/local HTTP; chưa chạy PostgreSQL hoặc nhiều instance triển khai thật.
- Snapshot Gateway và Merchant khác thời điểm; giao dịch/IPN đang chạy có thể tạo
  lệch tạm thời. Báo cáo cuối ngày cần chạy lại khi dữ liệu ổn định; không có
  distributed snapshot hoặc tự sửa dữ liệu tài chính.
- Chưa phân trang. Toàn bộ response B8 giới hạn 4 MiB, vượt thì báo lỗi rõ ràng,
  không trả kết quả bị cắt; chưa tối ưu throughput cho dữ liệu sản xuất lớn.
- Read timeout là socket timeout, không deadline tuyệt đối nếu peer gửi nhỏ giọt.
  HMAC không thay TLS; credential/secret mẫu và các giới hạn lease B6 vẫn áp dụng.
- Không tự sync missing, không xử lý refund, requeue vận hành hoặc retention audit.

## Giải thích ngắn năm câu hỏi của đề

1. Merchant giữ đơn; Gateway giữ giao dịch, registry/idempotency/IPN; Wallet giữ
   user/PIN; Bank nội bộ giữ số dư và ledger. Ba thành phần gọi nhau qua HTTP;
   riêng Bank tham gia cùng transaction Gateway để giữ nguyên tử chuyển tiền.
2. QR chứa TLV Merchant/amount/hạn/txnRef và CRC. CRC chỉ phát hiện lỗi; người sửa
   QR có thể tính lại CRC, nên Wallet phải hỏi Gateway và so số tiền thật.
3. UUID idempotency, khóa payment/account và unique DB ngăn chuyển tiền trùng.
   Replay dùng đúng kết quả đã commit, không sinh ledger hoặc bankRef mới.
4. Merchant kiểm HMAC để xác thực thông báo và dữ liệu; sau đó vẫn phải kiểm owner,
   amount, binding txnRef và trạng thái, khóa đơn để chống replay/cập nhật cạnh tranh.
5. Đối soát so hai tập kết quả độc lập. IPN mất khiến Gateway SUCCESS nhưng đơn còn
   WAITING_PAYMENT dù chuyển tiền đúng; B8 phát hiện lệch, B7 sync riêng mới sửa đơn.
