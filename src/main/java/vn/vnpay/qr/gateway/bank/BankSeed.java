package vn.vnpay.qr.gateway.bank;
import org.springframework.boot.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
@Component
public class BankSeed implements ApplicationRunner {
    private final AccountRepository accounts;
    public BankSeed(AccountRepository accounts) { this.accounts = accounts; }
    @Override @Transactional public void run(ApplicationArguments args) {
        seed("9000001", "SHOP ABC", 0); seed("1000001", "Customer 1", 1_000_000); seed("1000002", "Customer 2", 10_000);
    }
    private void seed(String number, String owner, long balance) {
        if (!accounts.existsById(number)) accounts.save(new Account(number, owner, balance));
    }
}
