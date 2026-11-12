package vn.vnpay.qr.wallet.config;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.wallet.domain.WalletUser;
import vn.vnpay.qr.wallet.repository.WalletUserRepository;

@Component
public class WalletUserSeed implements ApplicationRunner {
    private final WalletUserRepository users;
    private final BCryptPasswordEncoder bcrypt;
    public WalletUserSeed(WalletUserRepository users, BCryptPasswordEncoder bcrypt) { this.users = users; this.bcrypt = bcrypt; }
    @Override @Transactional public void run(ApplicationArguments arguments) {
        seed("U001", "Customer 1", "1000001"); seed("U002", "Customer 2", "1000002");
    }
    private void seed(String id, String name, String account) {
        if (!users.existsById(id)) users.save(new WalletUser(id, name, account, bcrypt.encode("123456")));
    }
}
