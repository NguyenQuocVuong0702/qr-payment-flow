package vn.vnpay.qr.wallet.repository;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vn.vnpay.qr.wallet.domain.WalletUser;

public interface WalletUserRepository extends JpaRepository<WalletUser, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from WalletUser u where u.userId = :userId")
    Optional<WalletUser> findForUpdate(@Param("userId") String userId);
}
