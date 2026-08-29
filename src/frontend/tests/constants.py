# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Constants and cryptographic fixtures used by frontend tests."""

import time

import jwt
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa


def _keypair():
    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    public_key = private_key.public_key()
    private_pem = private_key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.TraditionalOpenSSL,
        serialization.NoEncryption(),
    )
    public_pem = public_key.public_bytes(
        serialization.Encoding.PEM,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_pem, public_pem


EXAMPLE_PRIVATE_KEY, EXAMPLE_PUBLIC_KEY = _keypair()
FOREIGN_PRIVATE_KEY, _ = _keypair()
NOW = int(time.time())
EXAMPLE_USER = "jdoe"
EXAMPLE_ACCOUNT = "1234512345"
EXAMPLE_PAYLOAD = {
    "name": "Jane Doe",
    "user": EXAMPLE_USER,
    "acct": EXAMPLE_ACCOUNT,
    "iat": NOW,
    "exp": NOW + 3600,
}
EXAMPLE_TOKEN = jwt.encode(EXAMPLE_PAYLOAD, EXAMPLE_PRIVATE_KEY, algorithm="RS256")
EXPIRED_TOKEN = jwt.encode(
    {**EXAMPLE_PAYLOAD, "iat": NOW - 7200, "exp": NOW - 3600},
    EXAMPLE_PRIVATE_KEY,
    algorithm="RS256",
)
FOREIGN_TOKEN = jwt.encode(EXAMPLE_PAYLOAD, FOREIGN_PRIVATE_KEY, algorithm="RS256")

EXAMPLE_BALANCE = 123456
EXAMPLE_TRANSACTIONS = [
    {
        "timestamp": "2024-01-02T03:04:05.000000+0000",
        "fromAccountNum": "9999999999",
        "toAccountNum": EXAMPLE_ACCOUNT,
        "amount": 1000,
        "uuid": "credit",
    },
    {
        "timestamp": "2024-01-02T03:04:05.000000+0000",
        "fromAccountNum": EXAMPLE_ACCOUNT,
        "toAccountNum": "8888888888",
        "amount": 234,
        "uuid": "debit",
    },
]
EXAMPLE_CONTACTS = [
    {
        "label": "Savings",
        "account_num": "9999999999",
        "routing_num": "987654321",
        "is_external": False,
    },
    {
        "account_num": "8888888888",
        "routing_num": "987654321",
        "is_external": False,
    },
]


def sign_payload(payload=None):
    """Return a token signed by the test private key."""
    return jwt.encode(
        payload or EXAMPLE_PAYLOAD,
        EXAMPLE_PRIVATE_KEY,
        algorithm="RS256",
    )


def sign_with_cryptography(payload=None):
    """Exercise the generated key material through cryptography as well."""
    key = serialization.load_pem_private_key(EXAMPLE_PRIVATE_KEY, password=None)
    return key.sign(
        (payload or b"frontend-test"),
        padding.PKCS1v15(),
        hashes.SHA256(),
    )
