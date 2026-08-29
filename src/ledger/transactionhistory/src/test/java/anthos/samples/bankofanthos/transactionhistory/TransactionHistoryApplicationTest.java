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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Covers the metrics wiring of the application. Metrics export is disabled
 * through the ENABLE_METRICS environment variable set by surefire, so no
 * Stackdriver client is created.
 */
class TransactionHistoryApplicationTest {

    private static final String CONTAINER_NAME = "transactionhistory";

    private StackdriverMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = TransactionHistoryApplication.stackdriver();
    }

    @AfterEach
    void tearDown() {
        meterRegistry.close();
    }

    @Test
    @DisplayName("Given metrics export is disabled, "
        + "still build a meter registry that does not publish")
    void stackdriverRegistryHonoursDisabledMetricsExport() {
        // Then
        assertNotNull(meterRegistry);
        assertFalse(stackdriverConfig().enabled());
    }

    @Test
    @DisplayName("Given the pod environment, "
        + "describe the service as a Kubernetes container")
    void stackdriverConfigDescribesKubernetesContainer() {
        // When
        final StackdriverConfig config = stackdriverConfig();
        final Map<String, String> labels = config.resourceLabels();

        // Then
        assertAll(
            () -> assertEquals("k8s_container", config.resourceType()),
            () -> assertEquals(System.getenv("HOSTNAME"),
                labels.get("pod_name")),
            () -> assertEquals(CONTAINER_NAME, labels.get("container_name")),
            () -> assertEquals(System.getenv("NAMESPACE"),
                labels.get("namespace_name")));
    }

    @Test
    @DisplayName("Given no Stackdriver overrides, "
        + "fall back to metadata defaults")
    void stackdriverConfigHasNoPropertyOverrides() {
        // When
        final StackdriverConfig config = stackdriverConfig();

        // Then
        assertNull(config.get("stackdriver.projectId"));
        assertNotNull(config.projectId());
    }

    @Test
    @DisplayName("Given a shutdown signal, log and terminate cleanly")
    void destroyDoesNotFail() {
        new TransactionHistoryApplication().destroy();
    }

    private StackdriverConfig stackdriverConfig() {
        return (StackdriverConfig) TestFields.get(meterRegistry, "config");
    }
}
