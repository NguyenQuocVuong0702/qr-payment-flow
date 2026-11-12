# Báo cáo B3 — Wallet quét và xác minh QR

Ngày kiểm chứng: 2026-11-12, múi giờ +07:00.
Nguồn yêu cầu: [đề chính thức](../bai-tap-luong-thanh-toan-qr.md).
Đã hoàn thành B3; giữ API/nghiệp vụ B1/B2, dừng trước B4.

## Chức năng và API

`POST /api/wallet/scan` nhận đúng hai trường của đề:

```json
{"userId":"U001","qrString":"<qrString từ B2>"}
```

Wallet kiểm TLV và CRC cục bộ, lấy txnRef từ 62/05, gọi HTTP Gateway qua RestClient,
so amount, kiểm status/expireAt bằng Clock, rồi trả 200 với đúng bảy trường:

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

Ví dụ thời gian là fixture; dữ liệu trả thực tế lấy từ DB Gateway, không từ tên,
thành phố, description hoặc hạn in trong QR. Chỉ dùng amount QR để đối chiếu.

`GET /api/gateway/payments/{txnRef}` được thêm làm nguồn thông tin thật. Gateway
chỉ đọc DB; Wallet không import Gateway entity/service/repository. Wallet không giữ
transaction khi gọi HTTP và B3 không làm thay đổi trạng thái hoặc số dư.

## Bảo vệ GET dùng chung với B7

Không có nhánh GET ẩn danh hoặc tin `caller=wallet` do client tự khai:

| Caller | Credential | Dữ liệu được phép |
|---|---|---|
| Wallet | Authorization: Bearer với read-token riêng | txnRef, merchantName, merchantCity, amount, currency, description, status, expireAt |
| Merchant | X-Merchant-Id và X-Signature HMAC | Các trường tra cứu B7, chỉ giao dịch thuộc merchant đã xác thực |

Chữ ký GET Merchant theo canonical `merchantId=MC001&txnRef=TX00000001`.
Có bất kỳ header Merchant nào thì phải dùng nhánh HMAC; thiếu/sai/duplicate header
hoặc trộn Authorization bị từ chối. Không fallback sang Wallet kể cả token Wallet
đúng. Merchant hợp lệ nhưng không sở hữu giao dịch nhận 404 TXN_NOT_FOUND.

Response sau xác thực, kể cả lỗi lookup 404, ký JSON vào X-Signature bằng credential
tương ứng, kèm Cache-Control: no-store. Wallet verify chữ ký và đối chiếu txnRef trước
khi dùng response; không giữ hoặc dùng secret Merchant.

Credential nội bộ được cấu hình `QR_WALLET_READ_TOKEN` cho hai phía, mặc định mẫu
dev local `wallet-read-token-dev-only`. Không nhận token từ body scan hoặc QR. Đây
không phải xác thực người dùng: userId chỉ được validate hình thức, chưa có registry
Wallet/PIN ở B3. Không thêm OAuth hay framework bảo mật mới vào bài tập.

## TLV, CRC và quyết định nghiệp vụ

- Parser riêng trong Wallet giới hạn payload 512 ký tự UTF-16 như phạm vi B2 đang
  dùng; đọc length hai số ASCII theo code point, không trim input, reject header/value
  cắt ngắn, tag lặp, control character/unpaired surrogate. Đọc template 26 và 62 đầy đủ.
- Kiểm các tag 00/01/26/52/53/54/58/59/60/62/63 và tag con 26/00,26/01,62/05,62/08,
  62/09. Kiểm fixed value của QR động VND/VN/GUID; CRC là tag cuối, length 04, bốn hex.
- 62/05 đúng `01` + 10 số yyMMddHHmm + `TX` và 8 số của B2. Kiểm ngày/giờ hợp lệ;
  không dùng thời hạn QR để quyết định payable. Phần 20yy chỉ phục vụ kiểm lịch.
- Thuật toán CRC chuyển sang `common/qr/Crc16`; class công khai Crc16Calculator của
  Gateway vẫn giữ chữ ký/API và delegate. Đây là thay đổi code B2 duy nhất để dùng
  chung thuật toán, tránh Wallet import nghiệp vụ Gateway hoặc nhân đôi CRC.
- Thứ tự: cấu trúc/CRC → HTTP lookup → so amount → status/hạn. Sai amount được ưu
  tiên trước lỗi trạng thái/hạn, đúng thứ tự đề.
- Mâu thuẫn mô tả hết hạn của B3 được xử lý theo bảng mã và thiết kế đã duyệt:
  EXPIRED hoặc PENDING tại/quá expireAt → 422 TXN_EXPIRED; trạng thái khác PENDING
  và khác EXPIRED → 422 TXN_NOT_PAYABLE. Không tự ghi EXPIRED ở B3; job thuộc B7.
- userId thiếu/sai kiểu hoặc JSON request hỏng → 400 INVALID_REQUEST. qrString
  null/rỗng/sai cấu trúc → 422 INVALID_QR. Sai CRC → 422 CRC_MISMATCH; sai số tiền
  → 422 AMOUNT_MISMATCH; giao dịch không có → 404 TXN_NOT_FOUND.
- Mất kết nối/5xx Gateway → 502 GATEWAY_UNAVAILABLE; response JSON/HMAC/txnRef không
  hợp lệ → 502 INVALID_GATEWAY_RESPONSE. Mã technical mới cho GET thiếu credential
  Wallet là 401 INVALID_WALLET_CREDENTIALS; nhánh Merchant dùng INVALID_SIGNATURE.
  Tất cả lỗi dùng envelope errorCode/message/timestamp theo đề.

## Kết quả test thực tế

Đã chạy test B3 riêng: **67 PASS, 0 failure/error/skipped**. Sau đó chạy toàn bộ
test cũ và mới, bao gồm QrParserCompatibilityIT thực sự gọi project qr-parser gốc:

```powershell
$env:JAVA_HOME = Split-Path (Split-Path (Get-Command java).Source)
$env:MAVEN_USER_HOME = Join-Path $PWD '.build\maven-user-home'
.\mvnw.cmd -B "-Dmaven.repo.local=$PWD\.build\repository" `
  "-Dtest=*Test,*IT" "-Dqr.parser.url=http://127.0.0.1:<cong-parser>" test package
```

| Nhóm | PASS | FAIL / error / skipped |
|---|---:|---|
| Toàn bộ B1/B2 đã duyệt, giữ nguyên source test | 118 | 0 / 0 / 0 |
| PaymentQrParserTest | 45 | 0 / 0 / 0 |
| WalletScanHttpIntegrationTest | 22 | 0 / 0 / 0 |
| **Tổng** | **185** | **0 / 0 / 0** |

**BUILD SUCCESS**, thời gian Maven bản cuối **40.623 s**, executable JAR được tạo.
Surefire không chạy lặp trong phase package; 185 là số test, không cộng lượt chạy.
Lệnh mvnw test thông thường chọn 184 test nội bộ; IT ngoài cần bật tường minh cùng
URL project gốc. Không disable/skip test lỗi hoặc thay parser gốc bằng parser tự viết.

Các test đã xác nhận:

- Scan QR do API B2 thật sinh; HTTP Wallet → proxy → embedded Gateway thật; assert
  không có transaction tại thời điểm gọi HTTP; response đúng bảy trường, offset +07.
- Mỗi tag bắt buộc bị thiếu, nested tag thiếu/duplicate, TLV hỏng/truncated, CRC sai,
  sửa amount rồi tính lại CRC. QR không hợp lệ không gọi Gateway.
- Biên 62/05: prefix, thiếu timestamp/txnRef, thừa/thiếu ref, path injection, chữ
  thường, ký tự không số, tháng/ngày/giờ/phút không hợp lệ, leap day hợp lệ.
- TXN_NOT_FOUND; tất cả trạng thái non-PENDING; EXPIRED; Clock ngay trước và đúng
  expireAt. Dữ liệu tên/hạn QR bị sửa nhưng Gateway vẫn là nguồn hiển thị/hết hạn.
- HTTP drop/5xx, JSON response hỏng, chữ ký sai và response txnRef không khớp.
- GET anonymous/token sai bị chặn; projection Wallet không có orderId/bankRef/
  tài khoản; response ký đúng. Merchant HMAC đúng/sai/missing/duplicate/mixed,
  chữ ký dùng lại cho txnRef khác và truy cập chéo merchant đều được kiểm tra.
- 118 test cũ chạy lại nguyên vẹn, gồm Type04 API gốc: HTTP 200, status VALID, CRC VALID.
  Source parser gốc được đối chiếu checksum với bản build đã kiểm chứng B2, không đổi.

Bằng chứng cục bộ:
[test B3 riêng](../.build/b3-test.log), [full Maven log](../.build/b3-final-verify.log),
[Surefire reports](../target/surefire-reports/), [parser HTTP log](../.build/b3-qr-parser-http.log).

## Phạm vi và Git

Bắt đầu từ Git sạch tại `ca60f3e`, bảo toàn commit B1/B2 và file đề. Không sửa API,
service tạo đơn/giao dịch, entity, schema hoặc test B1/B2. Thêm config credential
Wallet, class GET mới, các package Wallet, CRC dùng chung, test và tài liệu; commit
riêng B3 được tạo sau khi test đạt.

Không triển khai B4, xác thực PIN, confirm payment, Bank/account/ledger, IPN,
workflow Merchant B7, job hết hạn hoặc đối soát. Scan chỉ là snapshot đọc tại thời
điểm kiểm tra; bước confirm tương lai phải kiểm lại trạng thái/hạn trong transaction.
Chưa kiểm chứng full B1–B8, tải lớn hoặc nhiều JVM; không tuyên bố parser Wallet là
validator EMV tổng quát hay đã hỗ trợ các loại QR ngoài cấu trúc B2 của bài tập.
