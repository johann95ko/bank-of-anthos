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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mockStatic;

import com.google.cloud.MetadataConfig;
import io.micrometer.stackdriver.StackdriverConfig;
import io.micrometer.stackdriver.StackdriverMeterRegistry;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.web.client.RestTemplate;

class LedgerWriterApplicationTest {

    private static final String PROJECT_ID = "test-project";
    private static final String ZONE = "us-central1-a";
    private static final String CLUSTER_NAME = "test-cluster";
    // Set by the surefire environmentVariables configuration.
    private static final String POD_NAME_ENV_VAR = "HOSTNAME";
    private static final String NAMESPACE_ENV_VAR = "NAMESPACE";

    private final LedgerWriterApplication application =
            new LedgerWriterApplication();

    @Test
    @DisplayName("The RestTemplate bean is created")
    void restTemplate() {
        // When
        final RestTemplate actualResult = application.restTemplate();

        // Then
        assertNotNull(actualResult);
    }

    @Test
    @DisplayName("Given the service is shutting down, the PreDestroy hook "
            + "completes without error")
    void destroy() {
        assertDoesNotThrow(() -> application.destroy());
    }

    @Test
    @DisplayName("Given every expected environment variable is set, "
            + "the Spring application is started")
    void mainStartsTheApplication() {
        try (MockedStatic<SpringApplication> springApplication =
                     mockStatic(SpringApplication.class)) {
            // Given
            final String[] args = {"--server.port=8080"};

            // When
            LedgerWriterApplication.main(args);

            // Then
            springApplication.verify(() -> SpringApplication.run(
                    LedgerWriterApplication.class, args));
        }
    }

    @Test
    @DisplayName("Given metrics export is disabled in the environment, the "
            + "Stackdriver registry is created with export disabled")
    void stackdriverIsCreatedWithMetricsExportDisabled() {
        try (MockedStatic<MetadataConfig> metadataConfig =
                     mockStatic(MetadataConfig.class)) {
            // Given
            metadataConfig.when(MetadataConfig::getProjectId)
                    .thenReturn(PROJECT_ID);

            // When
            final StackdriverMeterRegistry registry =
                    LedgerWriterApplication.stackdriver();
            try {
                // Then
                assertNotNull(registry);
                final StackdriverConfig config = configOf(registry);
                assertFalse(config.enabled());
                assertEquals(PROJECT_ID, config.projectId());
            } finally {
                registry.close();
            }
        }
    }

    @Test
    @DisplayName("Given the project cannot be read from the instance "
            + "metadata, the Stackdriver project id is empty")
    void stackdriverProjectIdIsEmptyWhenMetadataIsUnavailable() {
        try (MockedStatic<MetadataConfig> metadataConfig =
                     mockStatic(MetadataConfig.class)) {
            // Given
            metadataConfig.when(MetadataConfig::getProjectId)
                    .thenReturn(null);

            // When
            final StackdriverMeterRegistry registry =
                    LedgerWriterApplication.stackdriver();
            try {
                // Then
                assertEquals("", configOf(registry).projectId());
            } finally {
                registry.close();
            }
        }
    }

    @Test
    @DisplayName("Given the pod environment, the Stackdriver monitored "
            + "resource describes the running container")
    void stackdriverResourceDescribesTheContainer() {
        try (MockedStatic<MetadataConfig> metadataConfig =
                     mockStatic(MetadataConfig.class)) {
            // Given
            metadataConfig.when(MetadataConfig::getProjectId)
                    .thenReturn(PROJECT_ID);
            metadataConfig.when(MetadataConfig::getZone).thenReturn(ZONE);
            metadataConfig.when(MetadataConfig::getClusterName)
                    .thenReturn(CLUSTER_NAME);
            final String podName = System.getenv(POD_NAME_ENV_VAR);
            assertNotNull(podName, POD_NAME_ENV_VAR + " must be set");

            // When
            final StackdriverMeterRegistry registry =
                    LedgerWriterApplication.stackdriver();
            try {
                final StackdriverConfig config = configOf(registry);

                // Then
                assertEquals("k8s_container", config.resourceType());
                final Map<String, String> labels = config.resourceLabels();
                assertEquals(ZONE, labels.get("location"));
                assertEquals(podName.substring(0, podName.indexOf("-")),
                        labels.get("container_name"));
                assertEquals(podName, labels.get("pod_name"));
                assertEquals(CLUSTER_NAME, labels.get("cluster_name"));
                assertEquals(System.getenv(NAMESPACE_ENV_VAR),
                        labels.get("namespace_name"));
                // Keys outside the monitored resource are not configured.
                assertNull(config.get("stackdriver.step"));
            } finally {
                registry.close();
            }
        }
    }

    private StackdriverConfig configOf(StackdriverMeterRegistry registry) {
        try {
            Field field =
                    StackdriverMeterRegistry.class.getDeclaredField("config");
            field.setAccessible(true);
            return (StackdriverConfig) field.get(registry);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(
                    "Cannot read the Stackdriver configuration", e);
        }
    }
}
