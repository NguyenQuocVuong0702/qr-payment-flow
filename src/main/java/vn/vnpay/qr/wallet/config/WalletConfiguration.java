package vn.vnpay.qr.wallet.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({WalletGatewayProperties.class, WalletPaymentProperties.class})
public class WalletConfiguration {
    @org.springframework.context.annotation.Bean
    org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder bcryptPasswordEncoder() {
        return new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(10);
    }
}
