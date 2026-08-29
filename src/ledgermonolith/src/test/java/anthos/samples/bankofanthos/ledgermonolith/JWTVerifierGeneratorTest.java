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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JWTVerifierGeneratorTest {

    private JWTVerifierGenerator jwtVerifierGenerator;
    private KeyPair keyPair;

    @TempDir
    private Path tempDir;

    private static final int KEY_SIZE = 2048;
    private static final String JWT_ACCOUNT_KEY = "acct";
    private static final String ACCOUNT_NUM = "1234567890";

    @BeforeEach
    void setUp() throws NoSuchAlgorithmException {
        jwtVerifierGenerator = new JWTVerifierGenerator();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        keyPair = generator.generateKeyPair();
    }

    @Test
    @DisplayName("Given a PEM encoded public key, "
            + "the generated verifier accepts tokens signed with its key pair")
    void generateJWTVerifierAcceptsTokensSignedWithMatchingKey()
            throws IOException {
        // Given
        Path publicKeyPath = writePublicKey(true);
        String token = JWT.create()
                .withClaim(JWT_ACCOUNT_KEY, ACCOUNT_NUM)
                .sign(Algorithm.RSA256(null,
                        (RSAPrivateKey) keyPair.getPrivate()));

        // When
        JWTVerifier verifier = jwtVerifierGenerator.generateJWTVerifier(
                publicKeyPath.toString());

        // Then
        assertNotNull(verifier);
        DecodedJWT jwt = verifier.verify(token);
        assertEquals(ACCOUNT_NUM, jwt.getClaim(JWT_ACCOUNT_KEY).asString());
    }

    @Test
    @DisplayName("Given a public key without PEM headers, "
            + "the verifier is still generated")
    void generateJWTVerifierAcceptsKeyWithoutPemHeaders() throws IOException {
        // Given
        Path publicKeyPath = writePublicKey(false);

        // When
        JWTVerifier verifier = jwtVerifierGenerator.generateJWTVerifier(
                publicKeyPath.toString());

        // Then
        assertNotNull(verifier);
    }

    @Test
    @DisplayName("Given a missing public key file, "
            + "GenerateKeyException is thrown")
    void generateJWTVerifierFailsWhenKeyFileMissing() {
        // Given
        String missingPath = tempDir.resolve("missing.pub").toString();

        // When, Then
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                () -> jwtVerifierGenerator.generateJWTVerifier(missingPath));
    }

    @Test
    @DisplayName("Given a public key file that is not an RSA key, "
            + "GenerateKeyException is thrown")
    void generateJWTVerifierFailsWhenKeyIsNotRsa() throws IOException {
        // Given
        Path publicKeyPath = tempDir.resolve("garbage.pub");
        Files.writeString(publicKeyPath, "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})
                + "\n-----END PUBLIC KEY-----\n");

        // When, Then
        JWTVerifierGenerator.GenerateKeyException exceptionThrown =
                assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                        () -> jwtVerifierGenerator.generateJWTVerifier(
                                publicKeyPath.toString()));

        assertNotNull(exceptionThrown.getCause());
    }

    private Path writePublicKey(boolean withHeaders) throws IOException {
        String encoded = Base64.getMimeEncoder().encodeToString(
                ((RSAPublicKey) keyPair.getPublic()).getEncoded());
        String contents = withHeaders
                ? "-----BEGIN PUBLIC KEY-----\n" + encoded
                    + "\n-----END PUBLIC KEY-----\n"
                : encoded;
        Path publicKeyPath = tempDir.resolve("jwtRS256.key.pub");
        Files.writeString(publicKeyPath, contents);
        return publicKeyPath;
    }
}
