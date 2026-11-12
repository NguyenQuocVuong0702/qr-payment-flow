package vn.vnpay.qr.gateway.bank;
import org.springframework.data.jpa.repository.JpaRepository;
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> { }
