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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import java.util.LinkedList;

import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.data.domain.PageRequest;

class LedgerReaderCacheTest {

    private LoadingCache<String, AccountInfo> cache;

    @Mock
    private TransactionRepository dbRepo;

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String ACCOUNT_NUM = "1234567890";
    private static final Long BALANCE = 100L;
    private static final int CACHE_SIZE = 10;
    private static final int CACHE_MINUTES = 60;
    private static final int HISTORY_LIMIT = 5;

    @BeforeEach
    void setUp() {
        initMocks(this);
        LedgerReaderCache ledgerReaderCache = new LedgerReaderCache();
        TestFields.set(ledgerReaderCache, "dbRepo", dbRepo);
        cache = ledgerReaderCache.initializeCache(CACHE_SIZE, CACHE_MINUTES,
                LOCAL_ROUTING_NUM, HISTORY_LIMIT);
    }

    @Test
    @DisplayName("Given an account with transactions, "
            + "the cache loads its balance and history from the database")
    void loadReadsBalanceAndTransactionsFromDatabase() throws Exception {
        // Given
        LinkedList<Transaction> transactions = new LinkedList<>();
        transactions.add(mock(Transaction.class));
        when(dbRepo.findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM))
                .thenReturn(BALANCE);
        when(dbRepo.findForAccount(eq(ACCOUNT_NUM), eq(LOCAL_ROUTING_NUM),
                any())).thenReturn(transactions);

        // When
        AccountInfo actualResult = cache.get(ACCOUNT_NUM);

        // Then
        assertEquals(BALANCE, actualResult.getBalance());
        assertSame(transactions, actualResult.getTransactions());
        verify(dbRepo).findForAccount(ACCOUNT_NUM, LOCAL_ROUTING_NUM,
                PageRequest.of(0, HISTORY_LIMIT));
    }

    @Test
    @DisplayName("Given an account with no transactions, "
            + "the cache loads a zero balance")
    void loadDefaultsBalanceToZeroWhenAccountHasNoTransactions()
            throws Exception {
        // Given
        when(dbRepo.findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM))
                .thenReturn(null);
        when(dbRepo.findForAccount(eq(ACCOUNT_NUM), eq(LOCAL_ROUTING_NUM),
                any())).thenReturn(new LinkedList<>());

        // When
        AccountInfo actualResult = cache.get(ACCOUNT_NUM);

        // Then
        assertEquals(0L, actualResult.getBalance());
        assertEquals(0, actualResult.getTransactions().size());
    }

    @Test
    @DisplayName("Given a cached account, "
            + "a second read does not hit the database again")
    void loadIsCachedBetweenReads() throws Exception {
        // Given
        when(dbRepo.findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM))
                .thenReturn(BALANCE);
        when(dbRepo.findForAccount(eq(ACCOUNT_NUM), eq(LOCAL_ROUTING_NUM),
                any())).thenReturn(new LinkedList<>());

        // When
        cache.get(ACCOUNT_NUM);
        cache.get(ACCOUNT_NUM);

        // Then
        verify(dbRepo).findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM);
    }
}
