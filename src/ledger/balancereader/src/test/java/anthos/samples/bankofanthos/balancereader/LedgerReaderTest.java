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

package anthos.samples.bankofanthos.balancereader;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

class LedgerReaderTest {

    private LedgerReader ledgerReader;

    @Mock
    private TransactionRepository dbRepo;
    @Mock
    private LedgerReaderCallback callback;

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final Integer POLL_MS = 10;
    private static final long AWAIT_MILLIS = 5000;

    @BeforeEach
    void setUp() {
        initMocks(this);
        ledgerReader = new LedgerReader();
        ReflectionTestUtils.setField(ledgerReader, "dbRepo", dbRepo);
        ReflectionTestUtils.setField(ledgerReader, "pollMs", POLL_MS);
        ReflectionTestUtils.setField(ledgerReader,
            "localRoutingNum", LOCAL_ROUTING_NUM);
    }

    private Transaction transactionWithId(long id) {
        Transaction transaction = new Transaction();
        ReflectionTestUtils.setField(transaction, "transactionId", id);
        return transaction;
    }

    private void awaitBackgroundThreadDeath() throws InterruptedException {
        long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
        while (ledgerReader.isAlive()
            && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS);
        }
        assertFalse(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given a null callback, throw IllegalStateException")
    void startWithCallbackThrowsWhenCallbackIsNull() {
        assertThrows(IllegalStateException.class,
            () -> ledgerReader.startWithCallback(null));
    }

    @Test
    @DisplayName("Given no background thread started, isAlive returns true")
    void isAliveIsTrueBeforeStart() {
        assertTrue(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given new transactions in the ledger, "
        + "process each transaction through the callback")
    void processesNewTransactionsThroughCallback()
        throws InterruptedException {
        // Given
        Transaction transaction = transactionWithId(1L);
        when(dbRepo.latestTransactionId()).thenReturn(0L, 1L, 0L);
        when(dbRepo.findLatest(0L)).thenReturn(List.of(transaction));

        // When
        ledgerReader.startWithCallback(callback);

        // Then
        verify(callback, timeout(AWAIT_MILLIS))
            .processTransaction(transaction);
        awaitBackgroundThreadDeath();
    }

    @Test
    @DisplayName("Given the database is unreachable at init, "
        + "start reading from the beginning of the ledger")
    void startsAtBeginningOfLedgerWhenDatabaseUnavailableAtInit()
        throws InterruptedException {
        // Given
        Transaction transaction = transactionWithId(1L);
        when(dbRepo.latestTransactionId())
            .thenThrow(new ResourceAccessException("db unavailable"))
            .thenReturn(1L, 0L);
        when(dbRepo.findLatest(-1L)).thenReturn(List.of(transaction));

        // When
        ledgerReader.startWithCallback(callback);

        // Then
        verify(callback, timeout(AWAIT_MILLIS))
            .processTransaction(transaction);
        awaitBackgroundThreadDeath();
    }

    @Test
    @DisplayName("Given an empty ledger, treat the latest transaction id "
        + "as the starting sentinel")
    void treatsNullLatestTransactionIdAsEmptyLedger()
        throws InterruptedException {
        // Given
        Transaction transaction = transactionWithId(5L);
        when(dbRepo.latestTransactionId())
            .thenReturn(null, 5L)
            .thenReturn(null);
        when(dbRepo.findLatest(-1L)).thenReturn(List.of(transaction));

        // When
        ledgerReader.startWithCallback(callback);

        // Then
        verify(callback, timeout(AWAIT_MILLIS))
            .processTransaction(transaction);
        awaitBackgroundThreadDeath();
    }

    @Test
    @DisplayName("Given the remote ledger is behind the local id, "
        + "stop the background thread")
    void backgroundThreadDiesWhenRemoteLedgerOutOfSync()
        throws InterruptedException {
        // Given
        when(dbRepo.latestTransactionId()).thenReturn(5L, 1L);

        // When
        ledgerReader.startWithCallback(callback);

        // Then
        awaitBackgroundThreadDeath();
        verify(callback, never()).processTransaction(any(Transaction.class));
    }

    @Test
    @DisplayName("Given the database becomes unreachable while polling, "
        + "keep the previous transaction id")
    void keepsPreviousIdWhenDatabaseUnavailableDuringPoll()
        throws InterruptedException {
        // Given
        when(dbRepo.latestTransactionId())
            .thenReturn(0L)
            .thenThrow(new ResourceAccessException("db unavailable"))
            .thenReturn(-1L);

        // When
        ledgerReader.startWithCallback(callback);

        // Then
        awaitBackgroundThreadDeath();
        verify(callback, never()).processTransaction(any(Transaction.class));
    }

    @Test
    @DisplayName("Given the background thread is interrupted while sleeping, "
        + "keep polling the ledger")
    void keepsPollingWhenSleepInterrupted() throws InterruptedException {
        // Given
        ReflectionTestUtils.setField(ledgerReader, "pollMs", 5000);
        when(dbRepo.latestTransactionId()).thenReturn(5L, 1L);

        // When
        ledgerReader.startWithCallback(callback);
        Thread backgroundThread = (Thread) ReflectionTestUtils.getField(
            ledgerReader, "backgroundThread");
        backgroundThread.interrupt();

        // Then
        awaitBackgroundThreadDeath();
        verify(dbRepo, times(2)).latestTransactionId();
    }

    @Test
    @DisplayName("Given a poll that returns no transactions, "
        + "keep the previous transaction id")
    void keepsPreviousIdWhenPollReturnsNoTransactions()
        throws InterruptedException {
        // Given
        when(dbRepo.latestTransactionId()).thenReturn(0L, 3L, -1L);
        when(dbRepo.findLatest(0L))
            .thenReturn(Collections.emptyList());

        // When
        ledgerReader.startWithCallback(callback);

        // Then
        awaitBackgroundThreadDeath();
        verify(dbRepo, atLeastOnce()).findLatest(0L);
        verify(callback, never()).processTransaction(any(Transaction.class));
    }
}
