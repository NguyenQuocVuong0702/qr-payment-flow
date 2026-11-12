package vn.vnpay.qr.gateway.bank;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static vn.vnpay.qr.common.config.InfrastructureConfiguration.BUSINESS_ZONE;

@Service
public class BankTransferService {
    private final AccountRepository accounts;
    private final LedgerEntryRepository ledger;
    private final JdbcTemplate jdbc;
    public BankTransferService(AccountRepository accounts, LedgerEntryRepository ledger, JdbcTemplate jdbc) {
        this.accounts = accounts; this.ledger = ledger; this.jdbc = jdbc;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public BankTransferResult transfer(String txnRef, String payer, String payee, long amount, Instant now) {
        if (payer.equals(payee)) throw new IllegalStateException("Self transfer must be rejected before processing");
        // Explicit PK lookups in the same stable order for every transfer avoid account lock inversion.
        var locked = new HashMap<String, Account>();
        for (String number : new TreeSet<>(List.of(payer, payee))) {
            var account = accounts.findForUpdate(number);
            if (account.isEmpty()) return new BankTransferResult(null, "ACCOUNT_NOT_FOUND");
            locked.put(number, account.get());
        }
        var source = locked.get(payer); var target = locked.get(payee);
        if (source.getBalance() < amount) return new BankTransferResult(null, "INSUFFICIENT_FUNDS");
        Math.addExact(target.getBalance(), amount); // Overflow is a system error and must roll back, never wrap.
        Long sequence = jdbc.queryForObject("SELECT NEXT VALUE FOR bank_ref_seq", Long.class);
        String bankRef = "BK" + DateTimeFormatter.ofPattern("yyyyMMdd").withZone(BUSINESS_ZONE).format(now)
                + String.format(Locale.ROOT, "%06d", sequence);
        source.debit(amount);
        accounts.flush(); // Flush is not commit; failures at any later stage still roll back this debit.
        target.credit(amount);
        accounts.flush();
        ledger.saveAndFlush(new LedgerEntry(txnRef, bankRef, payer, "DEBIT", amount, now));
        ledger.saveAndFlush(new LedgerEntry(txnRef, bankRef, payee, "CREDIT", amount, now));
        return new BankTransferResult(bankRef, null);
    }
}
