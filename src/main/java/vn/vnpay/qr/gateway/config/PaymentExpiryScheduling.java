package vn.vnpay.qr.gateway.config;

import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;
import vn.vnpay.qr.gateway.service.PaymentExpiryJob;

@Configuration @EnableScheduling
@ConditionalOnProperty(name = "app.gateway.expiry.enabled", havingValue = "true", matchIfMissing = true)
public class PaymentExpiryScheduling {
    private final PaymentExpiryJob job;
    public PaymentExpiryScheduling(PaymentExpiryJob job) { this.job = job; }
    @Scheduled(fixedDelayString = "${app.gateway.expiry.interval-millis:60000}")
    public void poll() {
        try { job.runOnce(); }
        catch (RuntimeException ex) { LoggerFactory.getLogger(PaymentExpiryScheduling.class)
                .error("Payment expiry job failed: {}", ex.getClass().getSimpleName()); }
    }
}
