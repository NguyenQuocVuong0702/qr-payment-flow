package vn.vnpay.qr.gateway.ipn;
import java.time.Instant;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface IpnOutboxRepository extends JpaRepository<IpnOutbox, String> {
    @Query("select o.txnRef from IpnOutbox o where (o.state='READY' and o.nextAttemptAt<=:now) or (o.state='LEASED' and o.leaseUntil<=:now) order by o.createdAt,o.txnRef")
    List<String> candidates(@Param("now") Instant now, Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from IpnOutbox o where o.txnRef=:ref")
    Optional<IpnOutbox> lock(@Param("ref") String ref);
}
