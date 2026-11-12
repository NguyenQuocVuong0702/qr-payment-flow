package vn.vnpay.qr.wallet.service;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpay.qr.common.error.ApiException;
import vn.vnpay.qr.wallet.repository.WalletUserRepository;

@Service
public class WalletPinService {
    private final WalletUserRepository users;
    private final BCryptPasswordEncoder bcrypt;
    private final Clock clock;
    public WalletPinService(WalletUserRepository users, BCryptPasswordEncoder bcrypt, Clock clock) {
        this.users = users; this.bcrypt = bcrypt; this.clock = clock;
    }
    @Transactional public PinVerification verify(String userId, String pin) {
        var user = users.findForUpdate(userId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "WALLET_USER_NOT_FOUND", "Wallet user not found"));
        var now = clock.instant();
        if (user.getPinLockedUntil() != null) {
            if (now.isBefore(user.getPinLockedUntil())) return new PinVerification(null, "PIN_LOCKED");
            user.clearFailures();
        }
        if (pin.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72 || !bcrypt.matches(pin, user.getPinHash())) {
            user.failedAt(now);
            // Return outcome instead of throwing, so the failure count commits.
            return new PinVerification(null, user.getPinLockedUntil() == null ? "INVALID_PIN" : "PIN_LOCKED");
        }
        user.clearFailures();
        return new PinVerification(user.getAccountNo(), null);
    }
}
