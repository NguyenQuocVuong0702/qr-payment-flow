package vn.vnpay.qr.merchant.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.merchant.gateway")
public record MerchantGatewayProperties(@NotBlank String baseUrl, @NotBlank String secretKey) {
    @Override
    public String toString() { return "MerchantGatewayProperties[redacted]"; }
}
