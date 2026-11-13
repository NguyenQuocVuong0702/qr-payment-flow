package vn.vnpay.qr.gateway.ipn;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface IpnLogRepository extends JpaRepository<IpnLog, Long> {
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update IpnLog l set l.requestBody=:body where l.id=:id")
    void redactBody(@org.springframework.data.repository.query.Param("id") Long id,
            @org.springframework.data.repository.query.Param("body") String body);
    Optional<IpnLog> findByTxnRefAndAttempt(String txnRef, int attempt);
}
