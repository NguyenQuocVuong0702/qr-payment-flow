package vn.vnpay.qr;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import vn.vnpay.split.SplitApplications;

@SpringBootApplication
public class QrPaymentApplication {
    public static void main(String[] args) {
        var mode = new SimpleCommandLinePropertySource(args).getProperty("app.mode");
        if (mode == null || "all".equals(mode)) {
            SpringApplication.run(QrPaymentApplication.class, args);
        } else {
            SplitApplications.run(mode, args);
        }
    }
}
