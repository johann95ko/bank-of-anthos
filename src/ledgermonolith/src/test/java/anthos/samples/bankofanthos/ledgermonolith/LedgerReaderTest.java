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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;

class LedgerReaderTest {

    private LedgerReader ledgerReader;

    @Mock
    private TransactionRepository dbRepo;

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final long TRANSACTION_ID = 5L;
    private static final long OUT_OF_SYNC_TRANSACTION_ID = 0L;
    private static final int POLL_MS = 1;
    private static final int TIMEOUT_SECONDS = 10;

    @BeforeEach
    void setUp() {
        initMocks(this);
        ledgerReader = new LedgerReader();
        TestFields.set(ledgerReader, "dbRepo", dbRepo);
        TestFields.set(ledgerReader, "pollMs", POLL_MS);
        TestFields.set(ledgerReader, "localRoutingNum", LOCAL_ROUTING_NUM);
    }

    @Test
    @DisplayName("Given no callback, IllegalStateException is thrown")
    void startWithCallbackFailsWhenCallbackIsNull() {
        IllegalStateException exceptionThrown = assertThrows(
                IllegalStateException.class,
                () -> ledgerReader.startWithCallback(null));

        assertNotNull(exceptionThrown);
        assertEquals("callback is null", exceptionThrown.getMessage());
    }

    @Test
    @DisplayName("Given an unstarted reader, the reader reports itself alive")
    void isAliveWhenBackgroundThreadNotStarted() {
        assertTrue(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given new transactions in the ledger, "
            + "the callback is executed for each of them")
    void pollsNewTransactionsAndExecutesCallback() throws Exception {
        // Given the ledger is empty at init and holds one transaction after
        Transaction transaction = mock(Transaction.class);
        when(transaction.getTransactionId()).thenReturn(TRANSACTION_ID);
        when(dbRepo.latestTransactionId()).thenReturn(null,
                TRANSACTION_ID, OUT_OF_SYNC_TRANSACTION_ID);
        when(dbRepo.findLatest(-1L)).thenReturn(List.of(transaction));
        CountDownLatch processed = new CountDownLatch(1);

        // When
        ledgerReader.startWithCallback(txn -> {
            assertEquals(transaction, txn);
            processed.countDown();
        });

        // Then
        assertTrue(processed.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        // The reader stops once the ledger reports an older transaction id
        joinBackgroundThread();
        assertFalse(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given the ledger database is unreachable, "
            + "the reader starts without throwing")
    void startWithCallbackToleratesUnreachableDatabase() throws Exception {
        // Given
        when(dbRepo.latestTransactionId())
                .thenThrow(new DataAccessResourceFailureException("db down"))
                .thenReturn(-2L);

        // When, Then
        assertDoesNotThrow(() -> ledgerReader.startWithCallback(txn -> {
        }));
        joinBackgroundThread();
        assertFalse(ledgerReader.isAlive());
    }

    private void joinBackgroundThread() throws InterruptedException {
        Thread backgroundThread =
                (Thread) TestFields.get(ledgerReader, "backgroundThread");
        backgroundThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
    }
}
