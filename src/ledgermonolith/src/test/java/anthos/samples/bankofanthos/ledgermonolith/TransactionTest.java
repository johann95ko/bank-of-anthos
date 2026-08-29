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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private Transaction transaction;

    private static final long TRANSACTION_ID = 42L;
    private static final String FROM_ACCOUNT_NUM = "1234567890";
    private static final String FROM_ROUTING_NUM = "123456789";
    private static final String TO_ACCOUNT_NUM = "5678901234";
    private static final String TO_ROUTING_NUM = "567891234";
    private static final Integer AMOUNT = 3755;
    private static final String REQUEST_UUID = "uuid-1";

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
    @DisplayName("Given a persisted transaction, all fields are readable")
    void gettersReturnTransactionFields() {
        assertEquals(TRANSACTION_ID, transaction.getTransactionId());
        assertEquals(FROM_ACCOUNT_NUM, transaction.getFromAccountNum());
        assertEquals(FROM_ROUTING_NUM, transaction.getFromRoutingNum());
        assertEquals(TO_ACCOUNT_NUM, transaction.getToAccountNum());
        assertEquals(TO_ROUTING_NUM, transaction.getToRoutingNum());
        assertEquals(AMOUNT, transaction.getAmount());
    }

    @Test
    @DisplayName("Given no uuid on the request, return an empty string")
    void getRequestUuidDefaultsToEmptyString() {
        assertEquals("", transaction.getRequestUuid());
    }

    @Test
    @DisplayName("Given a uuid on the request, return the uuid")
    void getRequestUuidReturnsUuidWhenSet() {
        TestFields.set(transaction, "requestUuid", REQUEST_UUID);

        assertEquals(REQUEST_UUID, transaction.getRequestUuid());
    }

    @Test
    @DisplayName("Given a transaction, its string form renders the amount "
            + "in dollars")
    void toStringRendersAmountInDollars() {
        final String actualResult = transaction.toString();

        assertNotNull(actualResult);
        assertEquals(String.format("%s->$%.2f->%s",
                FROM_ACCOUNT_NUM, 37.55, TO_ACCOUNT_NUM), actualResult);
    }
}
