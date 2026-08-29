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

package anthos.samples.bankofanthos.transactionhistory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;

class LedgerReaderTest {

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final int POLL_MS = 5;
    private static final long TIMEOUT_MS = 5000;

    @Mock
    private TransactionRepository dbRepo;

    private LedgerReader ledgerReader;
    private List<Transaction> processed;

    @BeforeEach
    void setUp() {
        initMocks(this);
        processed = new CopyOnWriteArrayList<>();
        ledgerReader = new LedgerReader();
        TestFields.set(ledgerReader, "dbRepo", dbRepo);
        TestFields.set(ledgerReader, "pollMs", POLL_MS);
        TestFields.set(ledgerReader, "localRoutingNum", LOCAL_ROUTING_NUM);
    }

    @Test
    @DisplayName("Given a null callback, refuse to start")
    void startWithCallbackRejectsNullCallback() {
        assertThrows(IllegalStateException.class,
            () -> ledgerReader.startWithCallback(null));
        assertTrue(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given new transactions in the ledger, "
        + "hand each one to the callback and track the latest id")
    void backgroundThreadProcessesNewTransactions() throws Exception {
        // Given: the ledger holds transaction 5 at startup, then grows to 7,
        // and finally reports an id behind what has already been processed.
        when(dbRepo.latestTransactionId()).thenReturn(5L, 7L, 1L);
        when(dbRepo.findLatest(5L)).thenReturn(
            List.of(transactionWithId(6L), transactionWithId(7L)));

        // When
        ledgerReader.startWithCallback(processed::add);

        // Then: both new transactions were replayed and the reader shut
        // itself down once the ledger looked out of sync.
        awaitThreadDeath();
        assertEquals(2, processed.size());
        assertEquals(6L, processed.get(0).getTransactionId());
        assertEquals(7L, processed.get(1).getTransactionId());
        assertFalse(ledgerReader.isAlive());
    }

    @Test
    @DisplayName("Given an empty ledger, start reading from the beginning")
    void emptyLedgerStartsFromBeginningOfLedger() throws Exception {
        // Given: no transactions exist, so the latest id query returns null.
        when(dbRepo.latestTransactionId()).thenReturn(null, 0L, -2L);
        when(dbRepo.findLatest(-1L))
            .thenReturn(List.of(transactionWithId(0L)));

        // When
        ledgerReader.startWithCallback(processed::add);

        // Then: polling started at -1, the sentinel for an empty ledger.
        awaitThreadDeath();
        assertEquals(1, processed.size());
        assertEquals(0L, processed.get(0).getTransactionId());
    }

    @Test
    @DisplayName("Given the ledger database is unreachable, "
        + "keep the reader alive and retry")
    void unreachableDatabaseDoesNotKillTheReader() throws Exception {
        // Given: the database fails at init and on the first poll, then
        // reports an id behind the reader's position.
        when(dbRepo.latestTransactionId())
            .thenThrow(new DataAccessResourceFailureException("down"))
            .thenThrow(new DataAccessResourceFailureException("down"))
            .thenReturn(-2L);

        // When
        ledgerReader.startWithCallback(processed::add);

        // Then: no transaction was processed and the failures were swallowed.
        awaitThreadDeath();
        assertTrue(processed.isEmpty());
    }

    @Test
    @DisplayName("Given no new transactions, leave the latest id untouched")
    void idleLedgerProcessesNothing() throws Exception {
        // Given: the ledger never moves past the startup id, then rewinds.
        when(dbRepo.latestTransactionId()).thenReturn(9L, 9L, 8L);

        // When
        ledgerReader.startWithCallback(processed::add);

        // Then
        awaitThreadDeath();
        assertTrue(processed.isEmpty());
    }

    private void awaitThreadDeath() throws InterruptedException {
        Thread backgroundThread =
            (Thread) TestFields.get(ledgerReader, "backgroundThread");
        backgroundThread.join(TIMEOUT_MS);
        assertFalse(backgroundThread.isAlive(),
            "background thread should have stopped");
    }

    private Transaction transactionWithId(long id) {
        Transaction transaction = new Transaction();
        TestFields.set(transaction, "transactionId", id);
        TestFields.set(transaction, "fromAccountNum", "1234567890");
        TestFields.set(transaction, "fromRoutingNum", LOCAL_ROUTING_NUM);
        TestFields.set(transaction, "toAccountNum", "9876543210");
        TestFields.set(transaction, "toRoutingNum", LOCAL_ROUTING_NUM);
        TestFields.set(transaction, "amount", 100);
        return transaction;
    }
}
