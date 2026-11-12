package vn.vnpay.qr.gateway.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WalletReadProperties.class, WalletConfirmProperties.class})
public class GatewayReadConfiguration {
}
