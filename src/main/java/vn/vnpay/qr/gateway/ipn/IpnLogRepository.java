package vn.vnpay.qr.gateway.ipn;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface IpnLogRepository extends JpaRepository<IpnLog, Long> {
    Optional<IpnLog> findByTxnRefAndAttempt(String txnRef, int attempt);
}
