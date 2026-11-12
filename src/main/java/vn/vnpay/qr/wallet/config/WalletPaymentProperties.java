package vn.vnpay.qr.wallet.config;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@Validated @ConfigurationProperties("app.wallet.payment")
public record WalletPaymentProperties(@NotBlank String secret) {
    @Override public String toString() { return "WalletPaymentProperties[redacted]"; }
}
