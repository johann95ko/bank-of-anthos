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
import static org.mockito.Mockito.mock;

import java.util.ArrayDeque;
import java.util.Deque;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccountInfoTest {

    private static final Long BALANCE = 100L;

    @Test
    @DisplayName("Given a balance and transactions, both are readable")
    void gettersReturnConstructorArguments() {
        // Given
        Deque<Transaction> transactions = new ArrayDeque<>();
        transactions.add(mock(Transaction.class));

        // When
        AccountInfo accountInfo = new AccountInfo(BALANCE, transactions);

        // Then
        assertEquals(BALANCE, accountInfo.getBalance());
        assertSame(transactions, accountInfo.getTransactions());
    }
}
