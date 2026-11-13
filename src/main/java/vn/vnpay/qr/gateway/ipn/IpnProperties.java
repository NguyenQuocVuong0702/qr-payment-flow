package vn.vnpay.qr.gateway.ipn;

import java.time.Duration;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.gateway.ipn")
public record IpnProperties(@DefaultValue("PT1M") Duration lease,
        @DefaultValue("PT10S") Duration secondDelay, @DefaultValue("PT30S") Duration thirdDelay,
        @DefaultValue("1000") @Min(1) long pollMillis) {
    public IpnProperties {
        if (lease == null || lease.compareTo(Duration.ofSeconds(15)) < 0
                || secondDelay == null || secondDelay.isNegative() || thirdDelay == null || thirdDelay.isNegative()) {
            throw new IllegalArgumentException("IPN lease must be >= 15s and retry delays non-negative");
        }
    }
    public Duration retryDelay(int completedAttempt) { return completedAttempt == 1 ? secondDelay : thirdDelay; }
}
