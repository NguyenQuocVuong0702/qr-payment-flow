# B4 — Wallet xác nhận thanh toán

Đã triển khai POST /api/wallet/payments theo body userId, txnRef, amount, pin,
requestId. userId tra wallet_user, BCrypt kiểm PIN dưới khóa DB; payerAccount lấy
từ user đã xác minh. Không cho client thêm account nguồn vào body.

Sai PIN trả 401 INVALID_PIN; lần thứ ba trả 423 PIN_LOCKED, khóa 15 phút. Bộ đếm
và pin_locked_until commit trước khi trả lỗi. PIN đúng hoặc khóa đã hết hạn reset
bộ đếm. Clock kiểm biên; @Version và SELECT FOR UPDATE chống mất cập nhật.
PIN rỗng/sai nội dung tính là PIN sai; null hoặc quá 72 ký tự là request không hợp
lệ; chuỗi UTF-8 quá 72 byte được xem là PIN sai, không đưa quá giới hạn BCrypt.

Seed U001/account1000001 và U002/account1000002, tên Customer 1/2, PIN giả lập
123456 cho cả hai (U002 chưa được đề quy định). Chỉ lưu hash BCrypt strength 10;
restart không ghi đè hash, counter hoặc lock. Dependency chỉ spring-security-crypto,
không thêm Spring Security filter/login làm thay đổi API cũ.

HTTP confirm ngoài transaction PIN, giữ requestId, body đúng ba trường đề bài.
X-Wallet-Signature ký amount/payerAccount/requestId/txnRef bằng credential thanh
toán riêng QR_WALLET_PAYMENT_SECRET; không dùng token GET B3, không gửi PIN.
Response xác thực bằng X-Signature; timeout/kết nối/5xx/response không đáng tin trả
502 PAYMENT_RESULT_UNKNOWN và yêu cầu retry cùng requestId/txnRef/amount, không
tự kết luận thanh toán FAILED hay tự sinh UUID. WALLET_USER_NOT_FOUND là mã bổ
sung 404 cho user không có, lỗi business Gateway được chuyển theo whitelist.

Mốc B4 chạy thật: **197 PASS, 0 failure/error/skipped**, gồm 185 cũ + 12 B4.
Lệnh có cache cục bộ: mvnw.cmd -B -Dmaven.repo.local=<workspace>/.build/repository
"-Dtest=*Test,*IT" "-Dqr.parser.url=http://127.0.0.1:<port>" test package.
Log [.build/b4-final-verify.log](../.build/b4-final-verify.log), Surefire XML trong
target/surefire-reports. Kiểm timeout thật vượt read-timeout 5 giây, không bỏ test.

Test B4 dùng HTTP stub để kiểm Wallet; **ở commit B4 chưa có endpoint xử lý B5**,
chưa kiểm tính toàn vẹn tiền. Test DB tiền/ledger và integration HTTP Gateway thật
sẽ nằm trong commit B5 tiếp theo. Không có IPN hoặc ghi Merchant từ Gateway.
