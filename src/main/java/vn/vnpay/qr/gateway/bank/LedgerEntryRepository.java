package vn.vnpay.qr.gateway.bank;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    @Query("select e.accountNo from LedgerEntry e where e.bankRef = :bankRef and e.direction = 'CREDIT'")
    Optional<String> findCreditedAccount(String bankRef);
}
