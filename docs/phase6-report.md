# Báo cáo B6 — IPN bền vững

Ngày kiểm chứng: **2026-11-13**, múi giờ +07:00. Đề chính thức
[bai-tap-luong-thanh-toan-qr.md](../bai-tap-luong-thanh-toan-qr.md) và yêu cầu B6
hiện tại là nguồn ưu tiên. Bắt đầu từ Git sạch **ec5c571** của B5; bảo toàn lịch sử
B1–B5. Dừng tại B6, chưa triển khai workflow B7, job hết hạn hay đối soát B8.

## Luồng và tính nguyên tử

```mermaid
sequenceDiagram
    participant G as Gateway / Bank B5
    participant D as Gateway DB
    participant W as IPN Worker
    participant M as Merchant
    G->>D: Một transaction: payment, account, ledger, idempotency, outbox
    G->>D: COMMIT
    W->>D: Transaction claim: khóa job, cấp lease/token, log IN_PROGRESS
    D-->>W: COMMIT, trả snapshot URL/body
    W->>M: RestClient POST ipn_url + HMAC, ngoài DB transaction
    M->>M: Xác thực HMAC; transaction khóa đơn, kiểm dữ liệu và cập nhật
    M-->>W: Sau commit: code 00/02 hoặc lỗi
    W->>D: Transaction ghi log + DELIVERED hoặc lịch retry/EXHAUSTED
```

`ConfirmationTransaction` chỉ thêm dependency `IpnEnqueuer` và lời gọi enqueue sau
SUCCESS/FAILED. Enqueuer dùng MANDATORY và flush trong transaction B5. Nếu ghi
outbox lỗi, tất cả payment/account/ledger/idempotency rollback. Không chỉnh nghiệp
vụ `BankTransferService`, thứ tự khóa tiền hoặc fingerprint/replay UUID đã review.
PaymentCompleted giữ vai trò quan sát nội bộ; không dùng event trong RAM để dispatch.
Worker không nhìn thấy job chưa commit. Replay cùng UUID trả outcome cũ, không enqueue.

Gateway không import Merchant service/repository; việc gửi là HTTP thật, kể cả khi
hai thành phần chạy cùng ứng dụng. Merchant chỉ đọc bảng Order thuộc sở hữu mình.
`IpnWorker.runOnce` dùng NEVER, claim/complete nằm ở bean transaction riêng.

## Outbox, retry và phục hồi

| Thành phần | Trách nhiệm |
|---|---|
| `ipn_outbox` | PK txn_ref, snapshot URL/body đã ký, state, attempt_count, next_attempt_at, lease_until/token, created_at/version |
| `ipn_log` | UNIQUE(txn_ref,attempt), body, response_code, sent_at, finished_at, http_status/error_type |
| `IpnEnqueuer` | Ghi một job cùng transaction B5; registry là nguồn URL/secret |
| `IpnJobStore` | Claim/recheck dưới row lock; log, thu hồi lease và fence ACK muộn |
| `IpnHttpClient` | RestClient, connect 3s/read 5s, không redirect, giới hạn ACK 4096 byte |
| `IpnWorker` / scheduler | Poll 1s mặc định, xử lý tối đa 10 job mỗi tick, HTTP ngoài transaction |

State machine: `READY → LEASED → DELIVERED`; khi lỗi còn budget thì READY, hết budget
thì EXHAUSTED. Chỉ HTTP 2xx với code text `00` hoặc `02` kết thúc thành công. HTTP
khác 2xx (kể cả redirect), timeout, mất kết nối, ACK JSON hỏng hoặc code khác đều retry.
Không lưu/echo body phản hồi tùy ý; audit lưu mã lỗi và tên lớp exception, không secret.

**Tổng cộng ba attempt**, theo yêu cầu B6 đã làm rõ: lần đầu ngay sau commit khi
worker claim; lần hai +10s kể từ hoàn tất lần đầu; lần ba +30s kể từ hoàn tất lần hai.
Với HTTP tức thời, mốc 0/10/40s; không phải ba lần retry ngoài lần đầu, không chờ 60s
để gửi lần thứ tư. Clock điều khiển lịch test, không sleep 10/30s.

Claim đọc scalar txnRef rồi SELECT FOR UPDATE và kiểm lại state/due; hai worker
không cùng claim một lease còn hiệu lực. Không dùng khóa RAM. Log IN_PROGRESS và
attempt_count được commit trước HTTP để crash không làm mất dấu hoặc vượt budget.
Lease mặc định 60s, tối thiểu cấu hình 15s. Khi lease hết hạn, log cũ thành
UNKNOWN_AFTER_CRASH/LEASE_EXPIRED và lên lịch lần tiếp theo +10/+30 từ lúc thu hồi;
attempt 3 bỏ dở thành EXHAUSTED. Token ngăn ACK của owner cũ ghi đè state/log mới.

Restart đọc lại READY hoặc LEASED quá hạn từ DB, không cần event hay queue RAM.
Nếu Merchant đã commit mà ACK bị mất, retry nhận 02 và dừng. Hết budget vẫn giữ
EXHAUSTED cùng log để bước B7/B8 sau này nhận diện; không đổi kết quả thanh toán,
bankRef, số dư hoặc ledger. Không thêm endpoint vận hành requeue ở B6.

## HMAC và API Merchant

`POST /api/merchant/ipn`, Content-Type application/json. Ví dụ SUCCESS:

```json
{
  "txnRef":"TX00000001","orderId":"ORD20261102001","amount":55000,
  "status":"SUCCESS","bankRef":"BK20261102000001",
  "paidAt":"2026-11-02T09:03:12+07:00","signature":"<hex HMAC-SHA256>"
}
```

Gateway ký bằng secret_key MC001 trong registry; Merchant verify với secret MC001
cục bộ (`QR_MERCHANT_SECRET`, mẫu secret-mc001). Tái sử dụng `HmacSigner` B2:
bỏ signature/null, sort key, nối key=value bằng &, UTF-8, HMAC-SHA256 lowercase hex,
so byte constant-time. Không URL-encode, không trim hoặc đổi offset/format paidAt.
Request DTO giữ paidAt String, không parse rồi chuẩn hóa trước khi verify.

FAILED gửi **bỏ bankRef/paidAt**; hai giá trị không tham gia chuỗi ký. Inbound null
tường minh có cùng canonicalization với bỏ trường. Snapshot body/URL đã lưu được
retry nguyên vẹn, không ký lại bằng secret hoặc dữ liệu vừa thay trong registry.

Merchant kiểm HMAC trước, rồi khóa Order và kiểm owner local, amount, binding txnRef,
trạng thái; chỉ WAITING_PAYMENT được cập nhật. SUCCESS → PAID và lưu paidAt;
FAILED → PAYMENT_FAILED, paidAt null. Row lock bảo đảm hai IPN đồng thời chỉ một
lần cập nhật; replay hoặc IPN mâu thuẫn cho đơn đã terminal trả 02, giữ nguyên version.

| Điều kiện theo thứ tự | HTTP | Response |
|---|---|---|
| Thiếu/sai HMAC | 200 | `{"code":"97","message":"Invalid signature"}` |
| Đơn không tồn tại/không thuộc MC001 | 200 | `{"code":"01","message":"Order not found"}` |
| Amount không khớp | 200 | `{"code":"04","message":"Invalid amount"}` |
| txnRef không khớp đơn | 200 | `{"code":"01","message":"Order not found"}` |
| Đơn không còn WAITING_PAYMENT | 200 | `{"code":"02","message":"Order already confirmed"}` |
| Cập nhật hợp lệ và commit | 200 | `{"code":"00","message":"Confirm success"}` |

Sai binding dùng code 01 vì đề chưa có code riêng; không tự thêm mã nghiệp vụ IPN.
Payload có chữ ký đúng nhưng status ngoài SUCCESS/FAILED, SUCCESS thiếu bankRef hoặc
paidAt không parse ISO offset, FAILED chứa bankRef/paidAt khác null: HTTP 400
INVALID_REQUEST, không ghi đơn. JSON hỏng/sai kiểu/trường lạ dùng error handler
hiện có. Không chấp nhận phần dữ liệu không được ký do silently bỏ trường lạ.
HMAC đúng không có nghĩa được bỏ qua kiểm amount/txnRef/owner.

## Chạy thử và cấu hình

Chạy `mvnw.cmd spring-boot:run` với JDK 21, cổng mẫu 8080. Dùng
[requests/b6.http](../requests/b6.http): B1 tạo đơn, B2 tạo QR, Wallet payments B4/B5;
worker tự phát IPN sau commit. MC001.ipn_url phải trỏ đúng Merchant trước khi thanh
toán. B1 vẫn trả CREATED; B2 vẫn cập nhật WAITING_PAYMENT. Không thêm API dispatch.

Xem trong H2 console dev với JDBC URL đúng ứng dụng:

```sql
SELECT txn_ref,state,attempt_count,next_attempt_at,lease_until FROM ipn_outbox;
SELECT txn_ref,attempt,response_code,http_status,error_type,sent_at,finished_at
  FROM ipn_log ORDER BY id;
SELECT order_id,txn_ref,status,paid_at FROM orders;
```

Cấu hình `app.gateway.ipn`: enabled=true, poll-millis=1000, lease=PT1M,
second-delay=PT10S, third-delay=PT30S. Test profile tắt scheduler để cô lập fixture
B1–B5; vẫn tạo outbox. B6 test gọi worker qua proxy thật/Clock, IT bật scheduler.
`RestartPersistenceIT` B5 giữ nguyên assertion WAITING_PAYMENT và chỉ thêm flag
tắt worker; `IpnRestartIT` riêng kiểm chứng worker production thật.

## Kết quả kiểm thử

Lệnh full suite đã chạy qua Maven Wrapper, với project qr-parser gốc chạy HTTP trên
cổng ngẫu nhiên và Maven cache trong workspace:

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
$env:MAVEN_USER_HOME = Join-Path $PWD '.build\maven-user-home'
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.build\repository" `
  "-Dtest=*Test,*IT" "-Dqr.parser.url=http://127.0.0.1:<cổng-parser>" test package
```

Kết quả thực tế: **244 PASS, 0 FAIL, 0 errors, 0 skipped**, BUILD SUCCESS;
thời gian Maven **02:22**, kết thúc **2026-11-13 09:30:01 +07:00**.

| Nhóm | Test | Failure / error / skipped |
|---|---:|---|
| B1–B5 mặc định (giữ đủ 217 ca) | 217 | 0 / 0 / 0 |
| QrParserCompatibilityIT: project gốc, HTTP Type 04 | 1 | 0 / 0 / 0 |
| RestartPersistenceIT B5: hai production process / H2 file | 1 | 0 / 0 / 0 |
| IpnIntegrationTest B6: DB, HTTP và Clock | 24 | 0 / 0 / 0 |
| IpnRestartIT B6: ba production process / H2 file | 1 | 0 / 0 / 0 |
| **Tổng** | **244** | **0 / 0 / 0** |

JAR `target/qr-payment-flow-0.0.1-SNAPSHOT.jar` đóng gói thành công. Type04 gọi API
parser gốc, HTTP 200/VALID và CRC VALID; không dùng parser tự viết để tuyên bố tương
thích. Log cục bộ: `.build/b6-final-verify.log`, `.build/b6-qr-parser-http.log`,
XML `target/surefire-reports/TEST-*.xml`. Các thư mục build không đưa vào Git.
IpnIntegrationTest mất 8.161s, IpnRestartIT 48.517s trong full run. Lần chạy riêng
IT trước đó cũng PASS (56.87s); không có test bị bỏ qua hoặc assertion cũ bị xóa.

Các ca B6 đã bổ sung:

- SUCCESS/FAILED qua HTTP thật tới Merchant; FAILED bỏ trường optional, null có cùng HMAC.
- Rollback khi outbox lỗi sau flush Bank; retry Wallet không tạo job/tiền/ledger trùng.
- Kịch bản 9: HTTP 500, 500, 00; lịch 0/10/40, đúng ba dòng log, đơn PAID.
- Kịch bản 10: sai HMAC trả 97 trước cả order/amount; đơn không đổi.
- Sai order/amount/txnRef/merchant, ưu tiên mã lỗi; replay và IPN mâu thuẫn.
- Hai IPN đồng thời chỉ một 00/một 02; worker dừng khi nhận 02.
- Parameterized HTTP 500, 97, drop connection, JSON lỗi, redirect: ba attempt rồi EXHAUSTED.
- Timeout đọc thật, mất ACK sau commit Merchant, retry nhận 02; bất biến tiền/ledger/bankRef.
- Lease bỏ dở trước send/sau Merchant commit, fence ACK cũ, ba crash không tạo attempt 4.
- Hai worker cùng job, HTTP đang chờ: chỉ một request và một log.
- Payload signed nhưng status sai không làm hỏng Order.
- `IpnRestartIT`: ba production JVM, file H2 thật, crash READY rồi crash khi chờ ACK;
  Merchant PAID, lần restart cuối nhận 02, DELIVERED, hai log và đúng hai ledger.
  Test chỉnh lease quá hạn qua JDBC khi mọi process đã dừng và đặt retry delay 0;
  đây là fixture thời gian, không có hook test trong production.

## File thay đổi

- Thêm package `gateway/ipn`: properties, outbox/log entity và repository, enqueuer,
  claim/result DTO, job store, HTTP client, worker, scheduler.
- Merchant thêm IpnRequest/Response/Controller, MerchantIpnService, Order.confirmPayment.
- ConfirmationTransaction thêm enqueue; PaymentCompleted chỉ cập nhật mô tả vai trò.
- schema.sql bổ sung ipn_outbox/ipn_log và index/constraint, không sửa bảng tiền.
- Test thêm IpnIntegrationTest, IpnRestartIT; tắt scheduler trong test profile và IT B5.
- README, architecture, báo cáo này và requests/b6.http.

## Giới hạn và rủi ro còn lại

- Giao nhận có thể lặp, không thể xác định chính xác HTTP đã tới Merchant khi process
  chết. Attempt reserve trước gửi vẫn tiêu budget nếu chết trước network; log ghi
  UNKNOWN_AFTER_CRASH trung thực về kết quả chưa biết. Mất cả ba attempt giữ EXHAUSTED.
- Lease ngăn claim đồng thời trong thời gian hiệu lực; nếu process bị pause/partition
  lâu hơn lease, request cũ có thể còn sống khi lần mới được gửi. Token chỉ fence ACK
  ở Gateway, Merchant lock/idempotency bảo vệ tác dụng. Read timeout là giới hạn chờ
  socket, không phải bảo đảm tổng thời gian khi peer cố tình gửi nhỏ giọt. Không tuyên
  bố exactly-once HTTP hoặc loại bỏ mọi overlap ngoài giả định lease/timeout.
- Bài tập chạy local HTTP với secret mẫu. Triển khai thực tế cần cấu hình secret/URL
  tin cậy và HTTPS; thay secret phải phối hợp vì body đã ký được lưu cố định. Database
  audit chứa signature, không chứa raw PIN hoặc secret key trong ipn_outbox/ipn_log;
  chưa có retention/rotation/requeue vận hành.
- B6 không backfill job cho giao dịch SUCCESS/FAILED có trước nâng cấp; không tự sửa
  đơn lệch đã tồn tại. SQL chỉ thêm bảng/index; không reset dữ liệu hoặc seed balances.
- Đã kiểm chứng H2; chưa chạy PostgreSQL hay nhiều instance triển khai thực tế.
- Chưa có B7/B8, job hết hạn hoặc hoàn tiền. Báo cáo này không tuyên bố hoàn tất toàn
  bộ luồng B1–B8/kịch bản đối soát số 11–12.
