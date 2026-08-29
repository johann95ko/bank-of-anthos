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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class TransactionTest {

    private Transaction transaction;

    private static final long TRANSACTION_ID = 42L;
    private static final String FROM_ACCOUNT_NUM = "1234567890";
    private static final String FROM_ROUTING_NUM = "123456789";
    private static final String TO_ACCOUNT_NUM = "9876543210";
    private static final String TO_ROUTING_NUM = "987654321";
    private static final Integer AMOUNT = 1250;

    @BeforeEach
    void setUp() {
        transaction = new Transaction();
        ReflectionTestUtils.setField(transaction,
            "transactionId", TRANSACTION_ID);
        ReflectionTestUtils.setField(transaction,
            "fromAccountNum", FROM_ACCOUNT_NUM);
        ReflectionTestUtils.setField(transaction,
            "fromRoutingNum", FROM_ROUTING_NUM);
        ReflectionTestUtils.setField(transaction,
            "toAccountNum", TO_ACCOUNT_NUM);
        ReflectionTestUtils.setField(transaction,
            "toRoutingNum", TO_ROUTING_NUM);
        ReflectionTestUtils.setField(transaction, "amount", AMOUNT);
    }

    @Test
    @DisplayName("Given a populated transaction, getters return the fields")
    void gettersReturnFieldValues() {
        assertEquals(TRANSACTION_ID, transaction.getTransactionId());
        assertEquals(FROM_ACCOUNT_NUM, transaction.getFromAccountNum());
        assertEquals(FROM_ROUTING_NUM, transaction.getFromRoutingNum());
        assertEquals(TO_ACCOUNT_NUM, transaction.getToAccountNum());
        assertEquals(TO_ROUTING_NUM, transaction.getToRoutingNum());
        assertEquals(AMOUNT, transaction.getAmount());
    }

    @Test
    @DisplayName("Given an amount in cents, "
        + "toString formats the amount in dollars")
    void toStringFormatsAmountInDollars() {
        assertEquals("1234567890->$12.50->9876543210",
            transaction.toString());
    }
}
