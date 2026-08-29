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

package anthos.samples.bankofanthos.ledgerwriter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TransactionTest {

    private static final long TRANSACTION_ID = 42L;
    private static final String TRANSACTION_JSON = "{"
            + "\"fromAccountNum\":\"1234567890\","
            + "\"fromRoutingNum\":\"123456789\","
            + "\"toAccountNum\":\"5678901234\","
            + "\"toRoutingNum\":\"567891234\","
            + "\"amount\":3755,"
            + "\"uuid\":\"a-uuid\"}";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Given a transaction that has not been persisted, "
            + "the generated transaction id is unset")
    void getTransactionIdBeforeItIsGenerated() throws Exception {
        // Given
        final Transaction transaction =
                objectMapper.readValue(TRANSACTION_JSON, Transaction.class);

        // Then
        assertEquals(0L, transaction.getTransactionId());
    }

    @Test
    @DisplayName("Given a persisted transaction, "
            + "the generated transaction id is returned")
    void getTransactionIdAfterItIsGenerated() throws Exception {
        // Given
        final Transaction transaction =
                objectMapper.readValue(TRANSACTION_JSON, Transaction.class);
        final Field transactionId =
                Transaction.class.getDeclaredField("transactionId");
        transactionId.setAccessible(true);
        transactionId.set(transaction, TRANSACTION_ID);

        // Then
        assertEquals(TRANSACTION_ID, transaction.getTransactionId());
    }
}
