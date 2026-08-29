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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static final long TRANSACTION_ID = 42L;
    private static final String FROM_ACCOUNT_NUM = "1234567890";
    private static final String FROM_ROUTING_NUM = "123456789";
    private static final String TO_ACCOUNT_NUM = "9876543210";
    private static final String TO_ROUTING_NUM = "987654321";
    private static final Integer AMOUNT = 12345;

    private Transaction transaction;

    @BeforeEach
    void setUp() {
        transaction = new Transaction();
        TestFields.set(transaction, "transactionId", TRANSACTION_ID);
        TestFields.set(transaction, "fromAccountNum", FROM_ACCOUNT_NUM);
        TestFields.set(transaction, "fromRoutingNum", FROM_ROUTING_NUM);
        TestFields.set(transaction, "toAccountNum", TO_ACCOUNT_NUM);
        TestFields.set(transaction, "toRoutingNum", TO_ROUTING_NUM);
        TestFields.set(transaction, "amount", AMOUNT);
    }

    @Test
    @DisplayName("Given a persisted transaction, expose its ledger fields")
    void gettersReturnPersistedValues() {
        assertEquals(TRANSACTION_ID, transaction.getTransactionId());
        assertEquals(FROM_ACCOUNT_NUM, transaction.getFromAccountNum());
        assertEquals(FROM_ROUTING_NUM, transaction.getFromRoutingNum());
        assertEquals(TO_ACCOUNT_NUM, transaction.getToAccountNum());
        assertEquals(TO_ROUTING_NUM, transaction.getToRoutingNum());
        assertEquals(AMOUNT, transaction.getAmount());
    }

    @Test
    @DisplayName("Given an amount in cents, render it as dollars")
    void toStringFormatsAmountAsDollars() {
        assertEquals("1234567890->$123.45->9876543210",
            transaction.toString());
    }

    @Test
    @DisplayName("Given an amount below a dollar, keep two decimal places")
    void toStringFormatsSubDollarAmount() {
        TestFields.set(transaction, "amount", 5);

        assertEquals("1234567890->$0.05->9876543210",
            transaction.toString());
    }
}
