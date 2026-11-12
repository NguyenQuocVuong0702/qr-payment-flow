package vn.vnpay.qr.wallet.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.wallet.gateway")
public record WalletGatewayProperties(@NotBlank String baseUrl, @NotBlank String readToken) {
    @Override public String toString() { return "WalletGatewayProperties[redacted]"; }
}
