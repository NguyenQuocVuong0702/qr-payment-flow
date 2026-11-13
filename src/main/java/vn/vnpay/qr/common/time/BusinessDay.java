package vn.vnpay.qr.common.time;

import java.time.*;
import org.springframework.http.HttpStatus;
import vn.vnpay.qr.common.error.ApiException;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

/** Half-open business day in Vietnam, independent of the JVM/database timezone. */
public record BusinessDay(LocalDate date, Instant start, Instant end) {
    public static BusinessDay parse(String value) {
        try {
            if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new DateTimeException("Invalid date");
            var date = LocalDate.parse(value);
            if (date.getYear() < 1) throw new DateTimeException("Invalid year");
            return new BusinessDay(date, date.atStartOfDay(BUSINESS_ZONE).toInstant(),
                    date.plusDays(1).atStartOfDay(BUSINESS_ZONE).toInstant());
        } catch (DateTimeException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE", "date must be a valid yyyy-MM-dd calendar date");
        }
    }
    public boolean contains(Instant value) { return value != null && !value.isBefore(start) && value.isBefore(end); }
}
