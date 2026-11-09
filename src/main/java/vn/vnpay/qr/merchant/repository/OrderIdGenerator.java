package vn.vnpay.qr.merchant.repository;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Component
public class OrderIdGenerator {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(BUSINESS_ZONE);
    private final JdbcTemplate jdbc;

    public OrderIdGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String next(Instant now) {
        // A database sequence survives restart and allocates distinct IDs across concurrent requests.
        Long number = jdbc.queryForObject("SELECT NEXT VALUE FOR merchant_order_seq", Long.class);
        return "ORD" + DATE.format(now) + String.format(Locale.ROOT, "%03d", number);
    }
}
