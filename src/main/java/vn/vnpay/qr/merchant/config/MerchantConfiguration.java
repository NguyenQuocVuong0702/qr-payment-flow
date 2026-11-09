package vn.vnpay.qr.merchant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({MerchantProperties.class, MerchantGatewayProperties.class})
public class MerchantConfiguration {
}
