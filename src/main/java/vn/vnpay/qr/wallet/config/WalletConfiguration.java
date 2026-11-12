package vn.vnpay.qr.wallet.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WalletGatewayProperties.class)
public class WalletConfiguration {
}
