package demo.account;

import demo.event.AccountEvent;
import demo.event.AccountEventType;
import demo.event.EventService;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import java.util.Arrays;
import java.util.Objects;

import static demo.account.AccountStatus.*;
import static demo.account.AccountStatus.ACCOUNT_ACTIVE;
import static demo.account.AccountStatus.ACCOUNT_ARCHIVED;

/**
 * The {@link AccountService} provides transactional support for managing {@link Account}
 * entities. This service also provides event sourcing support for {@link AccountEvent}.
 * Events can be appended to an {@link Account}, which contains a append-only log of
 * actions that can be used to support remediation for distributed transactions that encountered
 * a partial failure.
 *
 * @author kbastani
 */
@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final EventService eventService;

    public AccountService(AccountRepository accountRepository, EventService eventService) {
        this.accountRepository = accountRepository;
        this.eventService = eventService;
    }

    /**
     * Create a new {@link Account} entity.
     *
     * @param account is the {@link Account} to create
     * @return the newly created {@link Account}
     */
    @CacheEvict(value = "account", key = "#account.getAccountId().toString()")
    public Account createAccount(Account account) {
        // Assert for uniqueness constraint
        Assert.isNull(accountRepository.findAccountByUserId(account.getUserId()),
                "An account with the supplied userId already exists");
        Assert.isNull(accountRepository.findAccountByAccountNumber(account.getAccountNumber()),
                "An account with the supplied account number already exists");

        // Save the account to the repository
        account = accountRepository.save(account);

        // Trigger the account creation event
        appendEvent(account.getAccountId(),
                new AccountEvent(AccountEventType.ACCOUNT_CREATED));

        return account;
    }

    /**
     * Get an {@link Account} entity for the supplied identifier.
     *
     * @param id is the unique identifier of a {@link Account} entity
     * @return an {@link Account} entity
     */
    @Cacheable(value = "account", key = "#id.toString()")
    public Account getAccount(Long id) {
        return accountRepository.findOne(id);
    }

    /**
     * Update an {@link Account} entity with the supplied identifier.
     *
     * @param id      is the unique identifier of the {@link Account} entity
     * @param account is the {@link Account} containing updated fields
     * @return the updated {@link Account} entity
     */
    @CachePut(value = "account", key = "#id.toString()")
    public Account updateAccount(Long id, Account account) {
        Assert.notNull(id, "Account id must be present in the resource URL");
        Assert.notNull(account, "Account request body cannot be null");

        if (account.getAccountId() != null) {
            Assert.isTrue(Objects.equals(id, account.getAccountId()),
                    "The account id in the request body must match the resource URL");
        } else {
            account.setAccountId(id);
        }

        Assert.state(accountRepository.exists(id),
                "The account with the supplied id does not exist");

        Account currentAccount = accountRepository.findOne(id);
        currentAccount.setUserId(account.getUserId());
        currentAccount.setAccountNumber(account.getAccountNumber());
        currentAccount.setDefaultAccount(account.getDefaultAccount());
        currentAccount.setStatus(account.getStatus());

        return accountRepository.save(currentAccount);
    }

    /**
     * Delete the {@link Account} with the supplied identifier.
     *
     * @param id is the unique identifier for the {@link Account}
     */
    @CacheEvict(value = "account", key = "#id.toString()")
    public Boolean deleteAccount(Long id) {
        Assert.state(accountRepository.exists(id),
                "The account with the supplied id does not exist");
        this.accountRepository.delete(id);
        return true;
    }

    /**
     * Append a new {@link AccountEvent} to the {@link Account} reference for the supplied identifier.
     *
     * @param accountId is the unique identifier for the {@link Account}
     * @param event     is the {@link AccountEvent} to append to the {@link Account} entity
     * @return the newly appended {@link AccountEvent}
     */
    public AccountEvent appendEvent(Long accountId, AccountEvent event) {
        Account account = getAccount(accountId);
        Assert.notNull(account, "The account with the supplied id does not exist");
        event.setAccount(account);
        event = eventService.createEvent(event);
        account.getEvents().add(event);
        accountRepository.save(account);
        return event;
    }

    /**
     * Apply an {@link AccountCommand} to the {@link Account} with a specified identifier.
     *
     * @param id             is the unique identifier of the {@link Account}
     * @param accountCommand is the command to apply to the {@link Account}
     * @return a hypermedia resource containing the updated {@link Account}
     */
    @CachePut(value = "account", key = "#id.toString()")
    public Account applyCommand(Long id, AccountCommand accountCommand) {
        Account account = getAccount(id);

        Assert.notNull(account, "The account for the supplied id could not be found");

        AccountStatus status = account.getStatus();

        switch (accountCommand) {
            case CONFIRM_ACCOUNT:
                Assert.isTrue(status == ACCOUNT_PENDING, "The account has already been confirmed");

                // Confirm the account
                Account updateAccount = account;
                updateAccount.setStatus(ACCOUNT_CONFIRMED);
                account = this.updateAccount(id, updateAccount);
                this.appendEvent(id, new AccountEvent(AccountEventType.ACCOUNT_CONFIRMED));
                break;
            case ACTIVATE_ACCOUNT:
                Assert.isTrue(status != ACCOUNT_ACTIVE, "The account is already active");
                Assert.isTrue(Arrays.asList(ACCOUNT_CONFIRMED, ACCOUNT_SUSPENDED, ACCOUNT_ARCHIVED)
                        .contains(status), "The account cannot be activated");

                // Activate the account
                account.setStatus(ACCOUNT_ACTIVE);
                account = this.updateAccount(id, account);
                this.appendEvent(id, new AccountEvent(AccountEventType.ACCOUNT_ACTIVATED));
                break;
            case SUSPEND_ACCOUNT:
                Assert.isTrue(status == ACCOUNT_ACTIVE, "An inactive account cannot be suspended");

                // Suspend the account
                account.setStatus(ACCOUNT_SUSPENDED);
                account = this.updateAccount(id, account);
                this.appendEvent(id, new AccountEvent(AccountEventType.ACCOUNT_SUSPENDED));
                break;
            case ARCHIVE_ACCOUNT:
                Assert.isTrue(status == ACCOUNT_ACTIVE, "An inactive account cannot be archived");

                // Archive the account
                account.setStatus(ACCOUNT_ARCHIVED);
                account = this.updateAccount(id, account);
                this.appendEvent(id, new AccountEvent(AccountEventType.ACCOUNT_ARCHIVED));
                break;
            default:
                Assert.notNull(accountCommand,
                        "The provided command cannot be applied to this account in its current state");
        }

        return account;
    }
}
