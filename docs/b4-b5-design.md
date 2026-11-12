# Thiết kế B4–B5

- B4: transaction ngắn khóa wallet_user, kiểm BCrypt và commit bộ đếm/khóa PIN.
  Sai PIN trả outcome để không rollback số lần sai. Sau transaction mới trả lỗi
  hoặc gọi HTTP. Đúng PIN lấy payerAccount từ bản ghi người dùng.
- B4/B5: credential HMAC thanh toán riêng, không dùng read-token B3. Ký
  amount/payerAccount/requestId/txnRef vào X-Wallet-Signature; ký response vào
  X-Signature. PIN không rời Wallet. Client không được gửi payerAccount tới Wallet.
- B5: một transaction nhận quyền sở hữu requestId bằng insert unique idempotency,
  khóa payment_txn, kiểm trạng thái/hạn/amount; Bank nội bộ propagation MANDATORY
  khóa hai account theo account_no tăng dần, cập nhật hai số dư và cặp ledger.
  Outcome cùng response_body/status được lưu trước commit; HTTP trả sau commit.
- Row idempotency đang xử lý chỉ tồn tại trong transaction chưa commit. Insert
  trùng requestId chờ transaction đầu rồi rollback do unique; đọc winner trong
  transaction mới. Fingerprint gồm txnRef/account/amount, khác payload trả 409
  IDEMPOTENCY_CONFLICT. Không lưu kết quả chưa hoàn tất hoặc lỗi hệ thống rollback.
- Lỗi nghiệp vụ hết hạn trả outcome, commit EXPIRED cùng response rồi mới trả 422;
  FAILED/INSUFFICIENT_FUNDS là outcome 200, không ledger. Lỗi hệ thống rollback tất cả.
- Chưa IPN/B6; Merchant vẫn WAITING_PAYMENT. B5 phát event trong transaction để
  B6 có thể đăng ký AFTER_COMMIT; hiện không có listener gửi HTTP.
