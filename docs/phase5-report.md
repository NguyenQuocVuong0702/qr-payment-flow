# Báo cáo B4–B5 — xác nhận thanh toán và Bank giả lập

Ngày kiểm chứng: **2026-11-12**, múi giờ +07:00. Đề chính thức
[bai-tap-luong-thanh-toan-qr.md](../bai-tap-luong-thanh-toan-qr.md) là nguồn ưu tiên.
Bắt đầu từ Git sạch `6c76ba3`, không sửa lịch sử B1–B3. B4 đã được triển khai/test/
commit riêng tại **76a10f3**, sau đó mới triển khai B5. Dừng B5, không có B6.

## API và kết quả

`POST /api/wallet/payments`:

```json
{
  "userId":"U001","txnRef":"TX00000001","amount":55000,
  "pin":"123456","requestId":"4f1c2a9e-7b3d-4c55-9a10-0e2b6f8d1a77"
}
```

Wallet khóa dòng User và kiểm BCrypt; chỉ sau khi transaction xác minh PIN commit,
mới gọi HTTP `POST /api/gateway/payments/{txnRef}/confirm`:

```json
{"payerAccount":"1000001","amount":55000,"requestId":"4f1c2a9e-7b3d-4c55-9a10-0e2b6f8d1a77"}
```

Body chỉ ba trường đề bài; PIN không được gửi sang Gateway. Source account là dữ
liệu User đã xác minh, không nhận payerAccount trong body Wallet. Credential confirm
riêng: `X-Wallet-Signature` HMAC-SHA256 của amount/payerAccount/requestId/txnRef;
khóa `QR_WALLET_PAYMENT_SECRET` cấu hình hai phía, mẫu local
`wallet-payment-secret-dev-only`. GET credential B3 không được quyền confirm.
Không cho trộn Authorization/header Merchant hoặc duplicate signature.

Response Gateway ký vào X-Signature; Wallet verify và kiểm txnRef/amount/schema.
200 SUCCESS ví dụ:

```json
{
  "txnRef":"TX00000001","status":"SUCCESS","amount":55000,
  "bankRef":"BK20261112000001","paidAt":"2026-11-12T09:03:12+07:00",
  "message":"Thanh toán thành công"
}
```

200 FAILED khi thiếu tiền:

```json
{
  "txnRef":"TX00000001","status":"FAILED","amount":55000,
  "message":"Thanh toán thất bại","errorCode":"INSUFFICIENT_FUNDS"
}
```

Không có bankRef/paidAt cho kết quả thiếu tiền. Response confirm thêm amount/message
để cung cấp đầy đủ hợp đồng kết quả B4; giữ các trường kết quả B5 trong đề.
Các lỗi HTTP khác dùng errorCode/message/timestamp. Bảng mã và ví dụ gọi ở
[README](../README.md#api-b4b5--thanh-toán), [requests/b4-b5.http](../requests/b4-b5.http).

## Luồng và transaction

```mermaid
sequenceDiagram
    participant C as Client
    participant W as Wallet
    participant G as Gateway
    participant B as Bank trong Gateway
    C->>W: userId, txnRef, amount, PIN, requestId
    W->>W: TX khóa wallet_user, BCrypt, lưu counter/lock, commit
    W->>G: HTTP confirm + HMAC, giữ UUID (không còn TX Wallet)
    G->>G: Authenticate, bắt đầu TX READ_COMMITTED
    G->>G: Replay hoặc reserve UUID unique; khóa payment_txn
    G->>G: Kiểm pending, expireAt, amount; PROCESSING
    G->>B: Gọi bean MANDATORY trong cùng TX
    B->>B: Khóa hai account theo PK; debit, credit, cặp ledger
    B-->>G: bankRef / lỗi nghiệp vụ
    G->>G: SUCCESS/FAILED, paidAt, response idempotency; COMMIT
    G-->>W: HTTP result đã ký
    W-->>C: Result, hoặc PAYMENT_RESULT_UNKNOWN nếu mất mạng
```

`ConfirmationTransaction.execute` là transaction thực sự; orchestrator ngoài nó
dùng propagation NEVER. `BankTransferService.transfer` là bean khác được gọi qua
Spring proxy, propagation MANDATORY, không thể chạy độc lập ngoài transaction.
Một datasource/transaction manager bao trùm JPA payment/account/ledger/idempotency;
JdbcTemplate cấp sequence cũng tham gia cùng kết nối quản lý của Spring.

Không có HTTP trong transaction chuyển tiền. Hai lệnh flush account và flush
ledger/cache **không phải commit**: lỗi sau bất kỳ flush nào rollback toàn bộ.
Không catch exception hệ thống rồi commit FAILED nửa vời. Chỉ outcome nghiệp vụ
đã xác định (thiếu tiền, thiếu account, hết hạn...) được commit cùng kết quả.

## Idempotency và concurrency

1. Tìm requestId trong DB trước tiên; nếu đã có, kiểm bộ giá trị txnRef/payerAccount/
   amount rồi trả nguyên http_status/response_body đã lưu. UUID được lưu dạng UUID,
   không phân biệt cách viết hoa/thường của cùng giá trị.
2. Nếu chưa có, insert reserve row với PK UUID và flush **trước payment lock**. Row
   chưa commit chưa có result, không nhìn thấy từ transaction READ_COMMITTED khác.
   Mỗi nhánh hoàn tất phải lưu cả status/body; không có nhánh commit reservation rỗng.
3. Request cùng UUID chờ insert unique: sau winner commit, transaction thua rollback
   và đọc winner qua read transaction mới. Không nuốt lỗi unique trong transaction
   rollback-only. Cơ chế này còn chặn cùng UUID dùng cho hai txn khác nhau.
4. PESSIMISTIC_WRITE payment_txn khiến các UUID khác nhau cùng txn chỉ có tối đa một
   lần PROCESSING/thành công. Lần sau trả TXN_NOT_PAYABLE; không tạo thêm bankRef.
5. Bank khóa từng account theo thứ tự String account_no tăng dần. Cùng source không
   chi vượt số dư; cùng target không mất credit; hai chiều không đảo thứ tự khóa.
   Account còn có @Version và CHECK balance >= 0; không dùng synchronized/cache RAM.
6. Mỗi transfer tạo hai ledger chung bank_ref, UNIQUE(bank_ref,direction) và
   UNIQUE(txn_ref,direction), FK account và txn. Payment bank_ref cũng UNIQUE.
   Tiền long/BIGINT; Math.addExact chặn overflow. Tổng audit dùng NUMERIC/BigDecimal
   để phép cộng nhiều account không bị tràn long, không dùng double.

Idempotency metadata không FK txn_ref/account vì cần lưu cả lỗi transaction/account
không tồn tại. Ledger chỉ tồn tại cho chuyển tiền thành công và có FK thực tế.
Không lưu PIN hoặc credential trong idempotency. Lỗi hệ thống rollback reservation,
cho phép retry đúng UUID thực hiện lại nếu chưa có lần nào commit. Sequence có thể
nhảy số sau rollback; không có bankRef/ledger thừa đã commit.

## Các quyết định chưa được đề quy định rõ

- User U002 dùng tên Customer 2 và PIN mẫu 123456; U001 tên Customer 1. Chỉ hash
  BCrypt strength 10 được lưu. Seed User/account chỉ insert khi thiếu, không reset
  balances, hash, số lần sai hoặc lock khi restart.
- Sai PIN lần 1/2: 401 INVALID_PIN; lần 3 và khi đang khóa: 423 PIN_LOCKED. Sau đúng
  15 phút được thử lại. PIN đúng reset counter. Mọi attempt sai, kể cả cùng UUID,
  đều đếm để không tạo lỗ hổng thử PIN không giới hạn bằng cách dùng lại requestId.
- `WALLET_USER_NOT_FOUND` 404 cho user không có; `ACCOUNT_NOT_FOUND` 404 làm payment
  FAILED, không có chuyển tiền. Source=target trả `INVALID_TRANSFER` 422 trước
  PROCESSING, giữ PENDING. Dùng lại UUID khác payload: `IDEMPOTENCY_CONFLICT` 409.
- Hết hạn tại chính expireAt cũng không được trả tiền; kiểm Clock sau payment lock.
  Commit EXPIRED cùng lỗi 422 TXN_EXPIRED rồi mới trả HTTP, tránh rollback mất state.
  Amount mismatch giữ PENDING và trả 422 AMOUNT_MISMATCH. Business outcome được cache,
  kể cả lỗi; muốn một lần thử mới với dữ liệu sửa cần UUID mới.
- Timeout/connect/5xx/response không xác thực ở Wallet trả 502 PAYMENT_RESULT_UNKNOWN.
  Không tự gán FAILED, không đổi requestId. Gateway đã commit thì retry trả kết quả
  cũ; Gateway rollback thì UUID chưa có outcome, retry có thể xử lý an toàn.
- PaymentCompleted publish trong transaction, sẵn cho listener AFTER_COMMIT ở B6.
  Hiện chỉ test listener quan sát; không production IPN listener, không HTTP/async
  retry/outbox. Cơ chế này chưa là giao nhận thông báo bền vững qua crash; B6 phải
  thiết kế phần đó, không chuyển tiền lại để bù thông báo thất lạc.

## Kiểm thử thực tế

**B4 riêng:** 197 PASS, gồm 185 cũ và 12 B4, trước commit 76a10f3.
**Bản cuối B5:** 219 PASS, **0 failures, 0 errors, 0 skipped**; Maven BUILD SUCCESS,
thời gian **1 phút 24 giây**, có tạo executable JAR.

| Nhóm test | PASS |
|---|---:|
| B1–B3 giữ nguyên source test, có Type04 gốc | 185 |
| WalletPaymentHttpTest: PIN, DB lock/counter, HTTP hợp đồng/timeout | 12 |
| ConfirmationIntegrationTest: Bank thật/H2/concurrency/fault | 21 |
| RestartPersistenceIT: hai process production thật + H2 file/HTTP | 1 |
| Tổng | **219** |

Lệnh full (parser gốc chạy ở cổng tạm do helper chuẩn bị):

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
$env:MAVEN_USER_HOME = Join-Path $PWD '.build\maven-user-home'
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.build\repository" `
  "-Dtest=*Test,*IT" "-Dqr.parser.url=http://127.0.0.1:<port>" test package
```

`mvnw test` thường chọn 217 test; full command có thêm Type04 và Restart IT.
Không bỏ qua test lỗi. B4 context lúc đầu trùng tên Clock test đã được sửa;
mọi test lỗi ban đầu đều được chạy lại thành công. Parser gốc được đối chiếu
checksum source (không thay đổi), chạy qua HTTP và vẫn trả Type04 VALID/CRC VALID.

### Các bất biến và lỗi ghi giữa chừng

- Tổng số dư trước/sau không đổi; từng bank_ref đúng hai entry và tổng DEBIT=CREDIT;
  mỗi txn chỉ có tối đa một bank_ref; retry không tạo entry/số dư mới.
- 8 request cùng UUID trả cùng một response và chỉ 2 ledger. Hai UUID cùng txn có
  một 200 SUCCESS và một 422. Cùng UUID cho hai txn đồng thời: một 200, một 409.
- Hai txn cùng source 80.000 trả 55.000: đúng một SUCCESS, một FAILED, còn 25.000.
  Hai nguồn cùng merchant credit 55.000 + 5.000 giữ đủ 60.000, không lost update.
- Sáu txn đồng thời mỗi txn 200.000 từ account 1.000.000: đúng năm SUCCESS, một
  FAILED; source=0, merchant=1.000.000, đúng 10 ledger.
- Fault injection sau SQL debit, credit, ledger 1, ledger 2 và response cache đã
  flush xuống H2: tất cả về balances ban đầu, payment PENDING, không ledger/cache;
  retry cùng UUID sau gỡ lỗi thành công đúng một lần. Bank service không được mock;
  repository spy chỉ chèn lỗi sau khi EntityManager thực sự flush vào DB.
- Credit overflow rollback toàn bộ. Bank MANDATORY gọi ngoài transaction bị từ chối.
  DB từ chối balance âm, ledger trùng và cache có body nhưng không status.
- Event AFTER_COMMIT chỉ thấy outcome đã commit; rollback không phát event tới
  listener đó. Replay không publish event hoặc cấp thêm bankRef.
- HTTP Wallet → Gateway thực sự bị drop response sau commit; Wallet trả UNKNOWN,
  retry đúng UUID thành SUCCESS nhưng chỉ một lần trừ tiền/cặp ledger.

### Restart và HTTP thực tế

RestartPersistenceIT chạy hai JVM production main qua ProcessBuilder, bỏ test-classes
khỏi classpath của process con, dùng H2 **file mới** riêng trong target/restart-b5-*.
Không sử dụng Spring test context/MockMvc/Bank mock trong các process con:

1. HTTP thật B1 → Merchant B2 → scan B3 → Wallet B4 → Gateway/Bank B5 trả SUCCESS
   55.000 VND. Làm U002 sai PIN ba lần, nhận PIN_LOCKED.
2. Dừng đột ngột process, không graceful shutdown; khởi động lại cùng file DB.
   U002 vẫn khóa; retry UUID của U001 trả nguyên response/bankRef, không trừ lại.
3. Tạo/thanh toán thêm 1.000 VND sau restart: txnRef/bankRef mới, seed không reset.
   Dừng process; kết nối JDBC độc lập xác nhận balances 944.000/10.000/56.000,
   tổng 1.010.000, bốn ledger/hai bankRef/hai idempotency rows. Hai đơn Merchant
   vẫn WAITING_PAYMENT. DB không có PIN plaintext và counter U002 vẫn 3.

Log/evidence cục bộ: [B4 full](../.build/b4-final-verify.log),
[B5 money](../.build/b5-money-test.log), [B5 full](../.build/b5-final-verify.log),
[Surefire XML](../target/surefire-reports/),
`target/restart-b5-*/first.log`, `second.log` và file H2 của test restart.

## Giới hạn còn lại

- Chưa B6–B8/IPN/retry IPN/job hết hạn/đối soát. Kịch bản #5 đã kiểm phần thiếu tiền
  Gateway/Bank; phần Merchant PAYMENT_FAILED đòi hỏi B6 nên chưa thực hiện. Các ca
  idempotency #6, concurrency #7, expiry #8 và PIN lock #12 đã có test tương ứng.
- Merchant vẫn WAITING_PAYMENT như giới hạn được giao, kể cả khi Gateway SUCCESS
  hoặc FAILED. Không gọi repository/service Merchant từ Gateway.
- Cơ chế xác thực vẫn là bài tập local: PIN và credential service, không thêm OAuth,
  ngân hàng thật hoặc bảo đảm giao nhận thông báo B6. Test dùng H2, chưa benchmark/
  nhiều JVM confirm đồng thời hoặc cơ sở dữ liệu khác. Restart đã kiểm hai process
  tuần tự, không tuyên bố mọi tình huống mất điện/hỏng phần cứng đã được chứng minh.
- Lock timeout/lỗi hệ thống có thể làm client chưa biết kết quả; bắt buộc retry cùng
  UUID. Request chưa commit không có cache outcome; không tự retry vô hạn bên trong
  transaction hoặc tự tạo yêu cầu thanh toán mới.

Commit B4: **76a10f3**. B5 được commit riêng sau các kiểm tra trên, không rewrite
commit B1–B4. Danh sách commit thực tế xem `git log --oneline`.
