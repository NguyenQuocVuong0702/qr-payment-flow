package vn.vnpay.qr.gateway.config;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
@Validated @ConfigurationProperties("app.gateway.confirm")
public record WalletConfirmProperties(@NotBlank String secret) {
    @Override public String toString() { return "WalletConfirmProperties[redacted]"; }
}
