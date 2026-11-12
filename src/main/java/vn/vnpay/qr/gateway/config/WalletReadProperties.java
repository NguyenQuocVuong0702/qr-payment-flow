package vn.vnpay.qr.gateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.gateway.wallet")
public record WalletReadProperties(@NotBlank String readToken) {
    @Override public String toString() { return "WalletReadProperties[redacted]"; }
}
