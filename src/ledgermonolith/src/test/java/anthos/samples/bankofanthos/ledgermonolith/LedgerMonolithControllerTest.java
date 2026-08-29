/*
 * Copyright 2020, Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.ledgermonolith;

import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION;
import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE;
import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_INVALID_NUMBER;
import static anthos.samples.bankofanthos.ledgermonolith.ExceptionMessages.EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;

import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.Claim;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;

class LedgerMonolithControllerTest {

    private LedgerMonolithController ledgerMonolithController;

    @Mock
    private JWTVerifier verifier;
    @Mock
    private LedgerReader ledgerReader;
    @Mock
    private LoadingCache<String, AccountInfo> ledgerReaderCache;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private TransactionValidator transactionValidator;
    @Mock
    private Transaction transaction;
    @Mock
    private DecodedJWT jwt;
    @Mock
    private Claim claim;

    private static final String VERSION = "v0.6.0";
    private static final String PUB_KEY_PATH = "/tmp/publickey";
    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String NON_LOCAL_ROUTING_NUM = "987654321";
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";
    private static final String NON_AUTHED_ACCOUNT_NUM = "9876543210";
    private static final String TO_ACCOUNT_NUM = "5678901234";
    private static final String BEARER_TOKEN = "Bearer abc";
    private static final String TOKEN = "abc";
    private static final Long SENDER_BALANCE = 40L;
    private static final Long RECEIVER_BALANCE = 50L;
    private static final int SMALLER_THAN_SENDER_BALANCE = 10;
    private static final int LARGER_THAN_SENDER_BALANCE = 1000;
    private static final int HISTORY_LIMIT = 100;
    private static final int EXTRA_LATENCY_MILLIS = 5;

    @BeforeEach
    void setUp() {
        initMocks(this);
        ledgerMonolithController = new LedgerMonolithController(PUB_KEY_PATH,
                ledgerReaderCache, verifier, transactionRepository,
                transactionValidator, ledgerReader, LOCAL_ROUTING_NUM,
                VERSION);
        TestFields.set(ledgerMonolithController, "historyLimit",
                HISTORY_LIMIT);

        when(verifier.verify(TOKEN)).thenReturn(jwt);
        when(jwt.getClaim(
                LedgerMonolithController.JWT_ACCOUNT_KEY)).thenReturn(claim);
        when(claim.asString()).thenReturn(AUTHED_ACCOUNT_NUM);
    }

    @Test
    @DisplayName("Given version number in the environment, "
            + "return a ResponseEntity with the version number")
    void version() {
        // When
        final ResponseEntity actualResult = ledgerMonolithController.version();

        // Then
        assertNotNull(actualResult);
        assertEquals(VERSION, actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the server is serving requests, return HTTP Status 200")
    void readiness() {
        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.readiness();

        // Then
        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the ledgerReader is alive, return HTTP Status 200")
    void livenessSucceedsWhenLedgerReaderIsAlive() {
        // Given
        when(ledgerReader.isAlive()).thenReturn(true);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.liveness();

        // Then
        assertNotNull(actualResult);
        assertEquals(LedgerMonolithController.READINESS_CODE,
                actualResult.getBody());
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the ledgerReader is not alive, return HTTP Status 500")
    void livenessFailsWhenLedgerReaderIsNotAlive() {
        // Given
        when(ledgerReader.isAlive()).thenReturn(false);

        // When
        final ResponseEntity actualResult = ledgerMonolithController.liveness();

        // Then
        assertNotNull(actualResult);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
    }

    // LEDGER WRITER

    @Test
    @DisplayName("Given the transaction is external, "
            + "the sender balance is not checked and HTTP Status 201 returned")
    void addTransactionSucceedsWhenSenderIsExternal(TestInfo testInfo)
            throws Exception {
        // Given
        givenTransaction(testInfo, NON_LOCAL_ROUTING_NUM,
                SMALLER_THAN_SENDER_BALANCE);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transactionRepository).save(transaction);
        verify(ledgerReaderCache, never()).get(any());
    }

    @Test
    @DisplayName("Given the transaction amount is covered by the cached "
            + "balance, return HTTP Status 201")
    void addTransactionSucceedsWhenSenderBalanceCoversAmount(TestInfo testInfo)
            throws Exception {
        // Given
        givenTransaction(testInfo, LOCAL_ROUTING_NUM,
                SMALLER_THAN_SENDER_BALANCE);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(
                new AccountInfo(SENDER_BALANCE, new ArrayDeque<>()));

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.CREATED, actualResult.getStatusCode());
        verify(transactionRepository).save(transaction);
    }

    @Test
    @DisplayName("Given the transaction amount exceeds the cached balance, "
            + "return HTTP Status 400")
    void addTransactionFailsWhenSenderBalanceTooLow(TestInfo testInfo)
            throws Exception {
        // Given
        givenTransaction(testInfo, LOCAL_ROUTING_NUM,
                LARGER_THAN_SENDER_BALANCE);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(
                new AccountInfo(SENDER_BALANCE, new ArrayDeque<>()));

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE,
                actualResult.getBody());
        verify(transactionRepository, never()).save(transaction);
    }

    @Test
    @DisplayName("Given the balance cache errors for the sender, the balance "
            + "is treated as unknown and HTTP Status 400 returned")
    void addTransactionFailsWhenBalanceCacheErrors(TestInfo testInfo)
            throws Exception {
        // Given
        givenTransaction(testInfo, LOCAL_ROUTING_NUM,
                SMALLER_THAN_SENDER_BALANCE);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenThrow(
                ExecutionException.class);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_INSUFFICIENT_BALANCE,
                actualResult.getBody());
    }

    @Test
    @DisplayName("Given the same transaction uuid is submitted twice, "
            + "the second submission returns HTTP Status 400")
    void addTransactionFailsWhenTransactionIsDuplicate(TestInfo testInfo) {
        // Given
        givenTransaction(testInfo, NON_LOCAL_ROUTING_NUM,
                SMALLER_THAN_SENDER_BALANCE);
        ledgerMonolithController.addTransaction(BEARER_TOKEN, transaction);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_DUPLICATE_TRANSACTION,
                actualResult.getBody());
        verify(transactionRepository, times(1)).save(transaction);
    }

    @Test
    @DisplayName("Given no Authorization header, return HTTP Status 400")
    void addTransactionFailsWhenAuthorizationHeaderIsNull() {
        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(null, transaction);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_WHEN_AUTHORIZATION_HEADER_NULL,
                actualResult.getBody());
    }

    @Test
    @DisplayName("Given the bearer token is not valid, "
            + "return HTTP Status 401")
    void addTransactionFailsWhenTokenIsNotValid() {
        // Given
        when(verifier.verify(TOKEN)).thenThrow(JWTVerificationException.class);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
        assertEquals(LedgerMonolithController.UNAUTHORIZED_CODE,
                actualResult.getBody());
    }

    @Test
    @DisplayName("Given the transaction fails validation, "
            + "return HTTP Status 400 with the validation message")
    void addTransactionFailsWhenTransactionIsInvalid(TestInfo testInfo) {
        // Given
        givenTransaction(testInfo, LOCAL_ROUTING_NUM,
                SMALLER_THAN_SENDER_BALANCE);
        doThrow(new IllegalArgumentException(EXCEPTION_MESSAGE_INVALID_NUMBER))
                .when(transactionValidator).validateTransaction(
                        LOCAL_ROUTING_NUM, AUTHED_ACCOUNT_NUM, transaction);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.BAD_REQUEST, actualResult.getStatusCode());
        assertEquals(EXCEPTION_MESSAGE_INVALID_NUMBER, actualResult.getBody());
    }

    @Test
    @DisplayName("Given the ledger database is unreachable, "
            + "return HTTP Status 500")
    void addTransactionFailsWhenDatabaseUnreachable(TestInfo testInfo) {
        // Given
        givenTransaction(testInfo, NON_LOCAL_ROUTING_NUM,
                SMALLER_THAN_SENDER_BALANCE);
        when(transactionRepository.save(transaction)).thenThrow(
                new ResourceAccessException("db down"));

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.addTransaction(BEARER_TOKEN,
                        transaction);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
        assertEquals("db down", actualResult.getBody());
    }

    // BALANCE READER

    @Test
    @DisplayName("Given the user is authenticated for the account, "
            + "return the cached balance")
    void getBalanceSucceedsWhenAccountMatchesAuthenticatedUser()
            throws Exception {
        // Given
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(
                new AccountInfo(SENDER_BALANCE, new ArrayDeque<>()));

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getBalance(BEARER_TOKEN,
                        AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
        assertEquals(SENDER_BALANCE, actualResult.getBody());
    }

    @Test
    @DisplayName("Given the user cannot access the account, "
            + "return HTTP Status 401")
    void getBalanceFailsWhenAccountDoesNotMatchAuthenticatedUser() {
        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getBalance(BEARER_TOKEN,
                        NON_AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the bearer token is not valid, return HTTP Status 401")
    void getBalanceFailsWhenTokenIsNotValid() {
        // Given
        when(verifier.verify(TOKEN)).thenThrow(JWTVerificationException.class);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getBalance(BEARER_TOKEN,
                        AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the cache errors for an authenticated user, "
            + "return HTTP Status 500")
    void getBalanceFailsWhenCacheErrors() throws Exception {
        // Given
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenThrow(
                ExecutionException.class);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getBalance(BEARER_TOKEN,
                        AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
    }

    // TRANSACTION HISTORY

    @Test
    @DisplayName("Given the user is authenticated for the account, "
            + "return the cached transaction history")
    void getTransactionsSucceedsWhenAccountMatchesAuthenticatedUser()
            throws Exception {
        // Given
        Deque<Transaction> history = new ArrayDeque<>(List.of(transaction));
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(
                new AccountInfo(SENDER_BALANCE, history));

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getTransactions(BEARER_TOKEN,
                        AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
        assertSame(history, actualResult.getBody());
    }

    @Test
    @DisplayName("Given extra latency is configured, "
            + "the transaction history is still returned")
    void getTransactionsAppliesExtraLatency() throws Exception {
        // Given
        TestFields.set(ledgerMonolithController, "extraLatencyMillis",
                EXTRA_LATENCY_MILLIS);
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenReturn(
                new AccountInfo(SENDER_BALANCE, new ArrayDeque<>()));

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getTransactions(BEARER_TOKEN,
                        AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.OK, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the user cannot access the account, "
            + "return HTTP Status 401")
    void getTransactionsFailsWhenAccountDoesNotMatchAuthenticatedUser() {
        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getTransactions(BEARER_TOKEN,
                        NON_AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the bearer token is not valid, return HTTP Status 401")
    void getTransactionsFailsWhenTokenIsNotValid() {
        // Given
        when(verifier.verify(TOKEN)).thenThrow(JWTVerificationException.class);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getTransactions(BEARER_TOKEN,
                        AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.UNAUTHORIZED, actualResult.getStatusCode());
    }

    @Test
    @DisplayName("Given the cache errors for an authenticated user, "
            + "return HTTP Status 500")
    void getTransactionsFailsWhenCacheErrors() throws Exception {
        // Given
        when(ledgerReaderCache.get(AUTHED_ACCOUNT_NUM)).thenThrow(
                ExecutionException.class);

        // When
        final ResponseEntity actualResult =
                ledgerMonolithController.getTransactions(BEARER_TOKEN,
                        AUTHED_ACCOUNT_NUM);

        // Then
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
                actualResult.getStatusCode());
    }

    // LEDGER READER CALLBACK

    @Test
    @DisplayName("Given a local transaction between two cached accounts, "
            + "both cached balances and histories are updated")
    void ledgerReaderCallbackUpdatesBothCachedAccounts() {
        // Given
        ConcurrentMap<String, AccountInfo> cacheContents =
                new ConcurrentHashMap<>();
        cacheContents.put(AUTHED_ACCOUNT_NUM,
                new AccountInfo(SENDER_BALANCE, new ArrayDeque<>()));
        cacheContents.put(TO_ACCOUNT_NUM,
                new AccountInfo(RECEIVER_BALANCE, new ArrayDeque<>()));
        when(ledgerReaderCache.asMap()).thenReturn(cacheContents);
        givenLocalTransactionBetweenAccounts();

        // When
        captureLedgerReaderCallback().processTransaction(transaction);

        // Then
        ArgumentCaptor<String> accountIds =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<AccountInfo> accountInfos =
                ArgumentCaptor.forClass(AccountInfo.class);
        verify(ledgerReaderCache, times(2)).put(accountIds.capture(),
                accountInfos.capture());
        assertEquals(List.of(AUTHED_ACCOUNT_NUM, TO_ACCOUNT_NUM),
                accountIds.getAllValues());
        assertEquals(SENDER_BALANCE - SMALLER_THAN_SENDER_BALANCE,
                accountInfos.getAllValues().get(0).getBalance().longValue());
        assertEquals(RECEIVER_BALANCE + SMALLER_THAN_SENDER_BALANCE,
                accountInfos.getAllValues().get(1).getBalance().longValue());
        assertSame(transaction,
                accountInfos.getAllValues().get(0).getTransactions()
                        .getFirst());
    }

    @Test
    @DisplayName("Given the cached history is at the history limit, "
            + "the oldest transaction is dropped")
    void ledgerReaderCallbackDropsOldestTransactionAtHistoryLimit() {
        // Given
        TestFields.set(ledgerMonolithController, "historyLimit", 1);
        Transaction oldTransaction = mock(Transaction.class);
        ConcurrentMap<String, AccountInfo> cacheContents =
                new ConcurrentHashMap<>();
        cacheContents.put(AUTHED_ACCOUNT_NUM, new AccountInfo(SENDER_BALANCE,
                new ArrayDeque<>(List.of(oldTransaction))));
        when(ledgerReaderCache.asMap()).thenReturn(cacheContents);
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getToAccountNum()).thenReturn(TO_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(NON_LOCAL_ROUTING_NUM);
        when(transaction.getAmount()).thenReturn(SMALLER_THAN_SENDER_BALANCE);

        // When
        captureLedgerReaderCallback().processTransaction(transaction);

        // Then
        ArgumentCaptor<AccountInfo> accountInfos =
                ArgumentCaptor.forClass(AccountInfo.class);
        verify(ledgerReaderCache).put(any(), accountInfos.capture());
        Deque<Transaction> history =
                accountInfos.getValue().getTransactions();
        assertEquals(1, history.size());
        assertSame(transaction, history.getFirst());
    }

    @Test
    @DisplayName("Given neither account is cached, the cache is not updated")
    void ledgerReaderCallbackIgnoresUncachedAccounts() {
        // Given
        when(ledgerReaderCache.asMap()).thenReturn(new ConcurrentHashMap<>());
        givenLocalTransactionBetweenAccounts();

        // When
        captureLedgerReaderCallback().processTransaction(transaction);

        // Then
        verify(ledgerReaderCache, never()).put(any(), any());
    }

    private void givenLocalTransactionBetweenAccounts() {
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getToAccountNum()).thenReturn(TO_ACCOUNT_NUM);
        when(transaction.getToRoutingNum()).thenReturn(LOCAL_ROUTING_NUM);
        when(transaction.getAmount()).thenReturn(SMALLER_THAN_SENDER_BALANCE);
    }

    private LedgerReaderCallback captureLedgerReaderCallback() {
        ArgumentCaptor<LedgerReaderCallback> callback =
                ArgumentCaptor.forClass(LedgerReaderCallback.class);
        verify(ledgerReader).startWithCallback(callback.capture());
        return callback.getValue();
    }

    private void givenTransaction(TestInfo testInfo, String fromRoutingNum,
            int amount) {
        when(transaction.getRequestUuid()).thenReturn(
                testInfo.getDisplayName());
        when(transaction.getFromAccountNum()).thenReturn(AUTHED_ACCOUNT_NUM);
        when(transaction.getFromRoutingNum()).thenReturn(fromRoutingNum);
        when(transaction.getAmount()).thenReturn(amount);
    }
}
