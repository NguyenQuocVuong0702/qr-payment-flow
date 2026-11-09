package vn.vnpay.qr.merchant.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.merchant")
public record MerchantProperties(@DefaultValue("MC001") @NotBlank @Size(max = 32) String id) {
}
