package vn.vnpay.qr.merchant.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MerchantProperties.class)
public class MerchantConfiguration {
}
