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

import com.google.common.cache.LoadingCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

class BalanceCacheTest {

    private BalanceCache balanceCache;

    @Mock
    private TransactionRepository dbRepo;

    private static final String LOCAL_ROUTING_NUM = "123456789";
    private static final String ACCOUNT_NUM = "1234567890";
    private static final Integer CACHE_SIZE = 1000;
    private static final long BALANCE = 500L;

    @BeforeEach
    void setUp() {
        initMocks(this);
        balanceCache = new BalanceCache();
        ReflectionTestUtils.setField(balanceCache, "dbRepo", dbRepo);
    }

    @Test
    @DisplayName("Given a cache miss, load the balance from the repository")
    void loadsBalanceFromRepositoryOnCacheMiss() throws Exception {
        // Given
        when(dbRepo.findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM))
            .thenReturn(BALANCE);
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When
        Long actualBalance = cache.get(ACCOUNT_NUM);

        // Then
        assertEquals(BALANCE, actualBalance);
        verify(dbRepo).findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM);
    }

    @Test
    @DisplayName("Given no balance in the repository, default to zero")
    void defaultsToZeroWhenRepositoryReturnsNull() throws Exception {
        // Given
        when(dbRepo.findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM))
            .thenReturn(null);
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When
        Long actualBalance = cache.get(ACCOUNT_NUM);

        // Then
        assertEquals(0L, actualBalance);
    }

    @Test
    @DisplayName("Given a cached account, do not reload from the repository")
    void servesRepeatedReadsFromCache() throws Exception {
        // Given
        when(dbRepo.findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM))
            .thenReturn(BALANCE);
        LoadingCache<String, Long> cache =
            balanceCache.initializeCache(CACHE_SIZE, LOCAL_ROUTING_NUM);

        // When
        cache.get(ACCOUNT_NUM);
        Long actualBalance = cache.get(ACCOUNT_NUM);

        // Then
        assertEquals(BALANCE, actualBalance);
        verify(dbRepo, times(1)).findBalance(ACCOUNT_NUM, LOCAL_ROUTING_NUM);
    }
}
