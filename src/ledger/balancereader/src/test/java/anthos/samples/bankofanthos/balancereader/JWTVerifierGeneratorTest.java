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

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class JWTVerifierGeneratorTest {

    private final JWTVerifierGenerator jwtVerifierGenerator =
        new JWTVerifierGenerator();

    private static KeyPair keyPair;

    private static final String JWT_ACCOUNT_KEY = "acct";
    private static final String ACCOUNT_NUM = "1234567890";
    private static final int KEY_SIZE = 2048;
    private static final int PEM_LINE_LENGTH = 64;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void generateKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(KEY_SIZE);
        keyPair = generator.generateKeyPair();
    }

    private Path writePublicKeyPem() throws IOException {
        String encodedKey = Base64.getMimeEncoder(PEM_LINE_LENGTH,
                System.lineSeparator().getBytes())
            .encodeToString(keyPair.getPublic().getEncoded());
        String pem = "-----BEGIN PUBLIC KEY-----" + System.lineSeparator()
            + encodedKey + System.lineSeparator()
            + "-----END PUBLIC KEY-----" + System.lineSeparator();
        Path keyFile = tempDir.resolve("publickey");
        Files.writeString(keyFile, pem);
        return keyFile;
    }

    @Test
    @DisplayName("Given a valid RSA public key file, "
        + "return a verifier that accepts tokens signed with the private key")
    void generatesVerifierThatAcceptsValidTokens() throws Exception {
        // Given
        Path keyFile = writePublicKeyPem();
        Algorithm algorithm = Algorithm.RSA256(
            (RSAPublicKey) keyPair.getPublic(),
            (RSAPrivateKey) keyPair.getPrivate());
        String token = JWT.create()
            .withClaim(JWT_ACCOUNT_KEY, ACCOUNT_NUM)
            .sign(algorithm);

        // When
        JWTVerifier verifier =
            jwtVerifierGenerator.generateJWTVerifier(keyFile.toString());

        // Then
        assertNotNull(verifier);
        DecodedJWT jwt = verifier.verify(token);
        assertEquals(ACCOUNT_NUM,
            jwt.getClaim(JWT_ACCOUNT_KEY).asString());
    }

    @Test
    @DisplayName("Given a missing public key file, "
        + "throw GenerateKeyException caused by an IOException")
    void throwsGenerateKeyExceptionWhenKeyFileIsMissing() {
        // Given
        String missingPath = tempDir.resolve("does-not-exist").toString();

        // When / Then
        JWTVerifierGenerator.GenerateKeyException exception = assertThrows(
            JWTVerifierGenerator.GenerateKeyException.class,
            () -> jwtVerifierGenerator.generateJWTVerifier(missingPath));
        assertTrue(exception.getCause() instanceof IOException);
    }

    @Test
    @DisplayName("Given a malformed public key file, "
        + "throw GenerateKeyException")
    void throwsGenerateKeyExceptionWhenKeyIsMalformed() throws IOException {
        // Given
        String pem = "-----BEGIN PUBLIC KEY-----" + System.lineSeparator()
            + "AAAA" + System.lineSeparator()
            + "-----END PUBLIC KEY-----" + System.lineSeparator();
        Path keyFile = tempDir.resolve("malformed");
        Files.writeString(keyFile, pem);

        // When / Then
        assertThrows(JWTVerifierGenerator.GenerateKeyException.class,
            () -> jwtVerifierGenerator.generateJWTVerifier(
                keyFile.toString()));
    }
}
