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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JWTVerifierGeneratorTest {

    private static final int KEY_SIZE = 2048;
    private static final String AUTHED_ACCOUNT_NUM = "1234567890";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;

    private final JWTVerifierGenerator generator = new JWTVerifierGenerator();

    @TempDir
    private Path tempDir;

    @BeforeAll
    static void generateKeys() throws Exception {
        KeyPairGenerator keyPairGenerator =
                KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(KEY_SIZE);
        keyPair = keyPairGenerator.generateKeyPair();
        otherKeyPair = keyPairGenerator.generateKeyPair();
    }

    @Test
    @DisplayName("Given a PEM encoded public key, return a verifier that "
            + "accepts tokens signed with the matching private key")
    void generateJWTVerifierAcceptsTokenSignedWithMatchingKey()
            throws IOException {
        // Given
        Path publicKeyPath = writePublicKeyPem(keyPair);
        String token = JWT.create()
                .withClaim(LedgerWriterController.JWT_ACCOUNT_KEY,
                        AUTHED_ACCOUNT_NUM)
                .sign(Algorithm.RSA256(null,
                        (RSAPrivateKey) keyPair.getPrivate()));

        // When
        JWTVerifier verifier =
                generator.generateJWTVerifier(publicKeyPath.toString());

        // Then
        assertNotNull(verifier);
        final DecodedJWT jwt = verifier.verify(token);
        assertEquals(AUTHED_ACCOUNT_NUM,
                jwt.getClaim(LedgerWriterController.JWT_ACCOUNT_KEY)
                        .asString());
    }

    @Test
    @DisplayName("Given a PEM encoded public key, the returned verifier "
            + "rejects tokens signed with a different private key")
    void generateJWTVerifierRejectsTokenSignedWithOtherKey()
            throws IOException {
        // Given
        Path publicKeyPath = writePublicKeyPem(keyPair);
        String token = JWT.create()
                .sign(Algorithm.RSA256(null,
                        (RSAPrivateKey) otherKeyPair.getPrivate()));

        // When
        JWTVerifier verifier =
                generator.generateJWTVerifier(publicKeyPath.toString());

        // Then
        assertThrows(SignatureVerificationException.class,
                () -> verifier.verify(token));
    }

    @Test
    @DisplayName("Given the public key file does not exist, "
            + "GenerateKeyException is thrown")
    void generateJWTVerifierFailsWhenKeyFileMissing() {
        // Given
        String missingPath = tempDir.resolve("absent.pub").toString();

        // When
        JWTVerifierGenerator.GenerateKeyException exceptionThrown =
                assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                        () -> generator.generateJWTVerifier(missingPath));

        // Then
        assertEquals("Cannot generate key: ", exceptionThrown.getMessage());
        assertInstanceOf(IOException.class, exceptionThrown.getCause());
    }

    @Test
    @DisplayName("Given the public key file holds data that is not an RSA "
            + "public key, GenerateKeyException is thrown")
    void generateJWTVerifierFailsWhenKeyIsNotAnRSAKey() throws IOException {
        // Given
        Path publicKeyPath = tempDir.resolve("invalid.pub");
        Files.write(publicKeyPath, pem(
                Base64.getEncoder().encodeToString(
                        "this is not a key".getBytes())).getBytes());

        // When
        JWTVerifierGenerator.GenerateKeyException exceptionThrown =
                assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
                        () -> generator.generateJWTVerifier(
                                publicKeyPath.toString()));

        // Then
        assertInstanceOf(InvalidKeySpecException.class,
                exceptionThrown.getCause());
    }

    @Test
    @DisplayName("GenerateKeyException keeps the message and the cause of "
            + "the failure that produced it")
    void generateKeyExceptionKeepsMessageAndCause() {
        // Given
        IOException cause = new IOException("no such file");

        // When
        JWTVerifierGenerator.GenerateKeyException exception =
                new JWTVerifierGenerator.GenerateKeyException(
                        "Cannot generate key: ", cause);

        // Then
        assertEquals("Cannot generate key: ", exception.getMessage());
        assertEquals(cause, exception.getCause());
    }

    private Path writePublicKeyPem(KeyPair pair) throws IOException {
        Path publicKeyPath = tempDir.resolve(
                "publickey-" + pair.hashCode() + ".pub");
        Files.write(publicKeyPath, pem(Base64.getEncoder().encodeToString(
                pair.getPublic().getEncoded())).getBytes());
        return publicKeyPath;
    }

    private String pem(String base64Key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + base64Key.replaceAll("(.{64})", "$1\n")
                + "\n-----END PUBLIC KEY-----\n";
    }
}
