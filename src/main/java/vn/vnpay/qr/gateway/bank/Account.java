package vn.vnpay.qr.gateway.bank;
import jakarta.persistence.*;
@Entity @Table(name = "account")
public class Account {
    @Id @Column(name = "account_no", length = 32) private String accountNo;
    @Column(name = "owner_name", nullable = false, length = 100) private String ownerName;
    @Column(nullable = false) private long balance;
    @Version @Column(nullable = false) private Long version;
    protected Account() { }
    public Account(String number, String owner, long balance) { accountNo = number; ownerName = owner; this.balance = balance; }
    public String getAccountNo() { return accountNo; }
    public long getBalance() { return balance; }
    public void debit(long amount) {
        if (amount <= 0 || balance < amount) throw new IllegalStateException("Invalid debit");
        balance -= amount;
    }
    public void credit(long amount) {
        if (amount <= 0) throw new IllegalStateException("Invalid credit");
        balance = Math.addExact(balance, amount);
    }
}
