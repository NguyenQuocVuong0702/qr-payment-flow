package vn.vnpay.split;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;

/** Separate Spring application contexts, each with its own HTTP routes and database ownership. */
public final class SplitApplications {
    private static final String COMMON = "vn.vnpay.qr.common";
    private static final String GATEWAY = "vn.vnpay.qr.gateway";
    private static final String MERCHANT = "vn.vnpay.qr.merchant";
    private static final String WALLET = "vn.vnpay.qr.wallet";

    private SplitApplications() { }

    public static void run(String mode, String[] args) {
        Class<?> application = switch (mode) {
            case "gateway" -> Gateway.class;
            case "merchant" -> Merchant.class;
            case "wallet" -> Wallet.class;
            default -> throw new IllegalArgumentException("app.mode must be all, gateway, merchant, or wallet");
        };
        var spring = new SpringApplication(application);
        spring.setAdditionalProfiles("split", "split-" + mode);
        spring.run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan({COMMON, GATEWAY})
    @EntityScan(GATEWAY)
    @EnableJpaRepositories(GATEWAY)
    static class Gateway {
        @Bean
        ApplicationRunner seedSplitMerchant(JdbcTemplate jdbc, @Value("${app.gateway.demo-ipn-url}") String ipnUrl,
                @Value("${app.merchant.gateway.secret-key}") String secret) {
            return args -> jdbc.update("""
                    INSERT INTO merchant(merchant_id, name, city, mcc, secret_key, ipn_url, account_no)
                    SELECT 'MC001', 'SHOP ABC', 'HANOI', '5499', ?, ?, '9000001'
                    WHERE NOT EXISTS (SELECT 1 FROM merchant WHERE merchant_id = 'MC001')
                    """, secret, ipnUrl);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan({COMMON, MERCHANT})
    @EntityScan(MERCHANT)
    @EnableJpaRepositories(MERCHANT)
    static class Merchant { }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan({COMMON, WALLET})
    @EntityScan(WALLET)
    @EnableJpaRepositories(WALLET)
    static class Wallet { }
}
