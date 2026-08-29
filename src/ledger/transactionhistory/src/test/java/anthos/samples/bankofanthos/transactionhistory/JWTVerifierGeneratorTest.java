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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.SignatureVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JWTVerifierGeneratorTest {

    private static final String JWT_ACCOUNT_KEY = "acct";
    private static final String ACCOUNT_NUM = "1234567890";
    private static final int KEY_SIZE = 2048;

    @TempDir
    Path tempDir;

    private JWTVerifierGenerator generator;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        generator = new JWTVerifierGenerator();
        KeyPairGenerator keyPairGenerator =
            KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(KEY_SIZE);
        keyPair = keyPairGenerator.generateKeyPair();
    }

    @Test
    @DisplayName("Given a PEM encoded public key, "
        + "build a verifier that accepts tokens signed with its private key")
    void generatesVerifierForMatchingKeyPair() throws Exception {
        // Given
        Path publicKeyPath = writePublicKey("publickey",
            keyPair.getPublic().getEncoded());
        String token = JWT.create()
            .withClaim(JWT_ACCOUNT_KEY, ACCOUNT_NUM)
            .sign(Algorithm.RSA256(null,
                (RSAPrivateKey) keyPair.getPrivate()));

        // When
        JWTVerifier verifier =
            generator.generateJWTVerifier(publicKeyPath.toString());

        // Then
        assertNotNull(verifier);
        DecodedJWT decoded = verifier.verify(token);
        assertEquals(ACCOUNT_NUM,
            decoded.getClaim(JWT_ACCOUNT_KEY).asString());
    }

    @Test
    @DisplayName("Given a token signed by another key, reject it")
    void verifierRejectsTokenFromDifferentKey() throws Exception {
        // Given
        Path publicKeyPath = writePublicKey("publickey",
            keyPair.getPublic().getEncoded());
        KeyPairGenerator otherGenerator =
            KeyPairGenerator.getInstance("RSA");
        otherGenerator.initialize(KEY_SIZE);
        KeyPair otherKeyPair = otherGenerator.generateKeyPair();
        String foreignToken = JWT.create()
            .withClaim(JWT_ACCOUNT_KEY, ACCOUNT_NUM)
            .sign(Algorithm.RSA256(null,
                (RSAPrivateKey) otherKeyPair.getPrivate()));

        // When
        JWTVerifier verifier =
            generator.generateJWTVerifier(publicKeyPath.toString());

        // Then
        assertThrows(SignatureVerificationException.class,
            () -> verifier.verify(foreignToken));
    }

    @Test
    @DisplayName("Given a missing key file, fail with GenerateKeyException")
    void missingKeyFileFailsVerifierGeneration() {
        // Given
        String missingPath = tempDir.resolve("absent").toString();

        // When / Then
        JWTVerifierGenerator.GenerateKeyException exception = assertThrows(
            JWTVerifierGenerator.GenerateKeyException.class,
            () -> generator.generateJWTVerifier(missingPath));
        assertTrue(exception.getCause() instanceof IOException);
    }

    @Test
    @DisplayName("Given key material that is not an RSA public key, "
        + "fail with GenerateKeyException")
    void malformedKeyFailsVerifierGeneration() throws Exception {
        // Given
        Path publicKeyPath =
            writePublicKey("malformed", new byte[] {1, 2, 3, 4});

        // When / Then
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
            () -> generator.generateJWTVerifier(publicKeyPath.toString()));
    }

    private Path writePublicKey(String fileName, byte[] keyBytes)
            throws IOException {
        String pem = "-----BEGIN PUBLIC KEY-----\n"
            + Base64.getMimeEncoder().encodeToString(keyBytes)
            + "\n-----END PUBLIC KEY-----\n";
        Path path = tempDir.resolve(fileName);
        Files.write(path, pem.getBytes());
        return path;
    }
}
