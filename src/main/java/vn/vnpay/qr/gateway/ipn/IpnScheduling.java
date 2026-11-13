package vn.vnpay.qr.gateway.ipn;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Configuration @EnableConfigurationProperties(IpnProperties.class)
public class IpnScheduling {
    @Configuration @EnableScheduling
    @ConditionalOnProperty(name = "app.gateway.ipn.enabled", havingValue = "true", matchIfMissing = true)
    static class Enabled {
        private final IpnWorker worker;
        Enabled(IpnWorker worker) { this.worker = worker; }
        @Scheduled(fixedDelayString = "${app.gateway.ipn.poll-millis:1000}")
        public void poll() {
            try { for (int i = 0; i < 10 && worker.runOnce(); i++) { /* bounded drain */ } }
            catch (RuntimeException ex) {
                // The lease survives a DB/worker failure; never print payloads or credentials.
                LoggerFactory.getLogger(IpnScheduling.class).error("IPN worker failed: {}", ex.getClass().getSimpleName());
            }
        }
    }
}
