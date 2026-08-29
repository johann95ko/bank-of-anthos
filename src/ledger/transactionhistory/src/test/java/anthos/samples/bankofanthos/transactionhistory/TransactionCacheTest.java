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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.util.Deque;
import java.util.LinkedList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;

class TransactionCacheTest {

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String ACCOUNT_NUM = "1234567890";
    private static final Integer CACHE_SIZE = 100;
    private static final Integer CACHE_MINUTES = 60;
    private static final Integer HISTORY_LIMIT = 10;

    @Mock
    private TransactionRepository dbRepo;

    private TransactionCache transactionCache;
    private LoadingCache<String, Deque<Transaction>> cache;

    @BeforeEach
    void setUp() {
        initMocks(this);
        transactionCache = new TransactionCache();
        TestFields.set(transactionCache, "dbRepo", dbRepo);
        cache = transactionCache.initializeCache(CACHE_SIZE, CACHE_MINUTES,
            LOCAL_ROUTING_NUM, HISTORY_LIMIT);
    }

    @Test
    @DisplayName("Given an uncached account, "
        + "load its transactions from the database once")
    void cacheLoadsTransactionsFromDatabase() throws Exception {
        // Given
        LinkedList<Transaction> history = new LinkedList<>();
        history.add(new Transaction());
        when(dbRepo.findForAccount(eq(ACCOUNT_NUM), eq(LOCAL_ROUTING_NUM),
            any(Pageable.class))).thenReturn(history);

        // When: the same account is requested twice
        Deque<Transaction> firstResult = cache.get(ACCOUNT_NUM);
        Deque<Transaction> secondResult = cache.get(ACCOUNT_NUM);

        // Then: the second read is served from the cache
        assertSame(history, firstResult);
        assertSame(firstResult, secondResult);
        verify(dbRepo, times(1)).findForAccount(eq(ACCOUNT_NUM),
            eq(LOCAL_ROUTING_NUM), any(Pageable.class));
    }

    @Test
    @DisplayName("Given a history limit, page the database query to that size")
    void cachePagesQueryToHistoryLimit() throws Exception {
        // Given
        when(dbRepo.findForAccount(eq(ACCOUNT_NUM), eq(LOCAL_ROUTING_NUM),
            any(Pageable.class))).thenReturn(new LinkedList<>());
        ArgumentCaptor<Pageable> pager =
            ArgumentCaptor.forClass(Pageable.class);

        // When
        cache.get(ACCOUNT_NUM);

        // Then
        verify(dbRepo).findForAccount(eq(ACCOUNT_NUM), eq(LOCAL_ROUTING_NUM),
            pager.capture());
        assertEquals(0, pager.getValue().getPageNumber());
        assertEquals(HISTORY_LIMIT, pager.getValue().getPageSize());
    }

    @Test
    @DisplayName("Given the database is unreachable, "
        + "surface the failure to the caller")
    void cachePropagatesDatabaseFailure() {
        // Given
        when(dbRepo.findForAccount(eq(ACCOUNT_NUM), eq(LOCAL_ROUTING_NUM),
            any(Pageable.class)))
            .thenThrow(new DataAccessResourceFailureException("down"));

        // When / Then
        assertThrows(UncheckedExecutionException.class,
            () -> cache.get(ACCOUNT_NUM));
    }
}
