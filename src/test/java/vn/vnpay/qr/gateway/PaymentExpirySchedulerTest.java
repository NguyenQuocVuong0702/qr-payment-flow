package vn.vnpay.qr.gateway;

import java.time.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.vnpay.qr.common.crypto.HmacSigner;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:expiry-scheduler-b7;DB_CLOSE_DELAY=-1",
        "app.gateway.expiry.enabled=true","app.gateway.expiry.interval-millis=25"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(PaymentExpirySchedulerTest.TestConfig.class)
class PaymentExpirySchedulerTest {
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired HmacSigner hmac;
    @Test void configuredScheduledJobRunsWithoutManuallyCallingTheWorker() throws Exception {
        var body=mapper.createObjectNode().put("merchantId","MC001").put("orderId","SCHEDULED1").put("amount",55000)
                .put("description","Ao thun").put("expireMinutes",1);body.put("signature",hmac.sign(body,"secret-mc001"));
        String ref=mapper.readTree(mvc.perform(post("/api/gateway/payments").contentType("application/json").content(body.toString()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("txnRef").asText();
        assertThat(jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?",String.class,ref)).isEqualTo("PENDING");
        clock.now.updateAndGet(value->value.plusSeconds(60));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!"EXPIRED".equals(jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?",String.class,ref))&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_txn WHERE txn_ref=?",String.class,ref)).isEqualTo("EXPIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ipn_outbox",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT SUM(balance) FROM account",Long.class)).isEqualTo(1010000);
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-13T03:00:00Z"));
        public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return Clock.fixed(now.get(),zone);}public Instant instant(){return now.get();}
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestConfig {@Bean @Primary MutableClock scheduledExpiryClock(){return new MutableClock();}}
}
