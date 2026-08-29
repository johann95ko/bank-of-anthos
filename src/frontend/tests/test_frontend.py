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

"""Tests for the frontend Flask application."""

import json
import importlib.util
import os
import sys
import unittest
from urllib.parse import unquote_plus
from unittest.mock import MagicMock, Mock, mock_open, patch

from requests.exceptions import HTTPError, RequestException

# Prefer the service's flat-module layout over the repository package name.
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
if "frontend" in sys.modules:
    del sys.modules["frontend"]
_frontend_spec = importlib.util.spec_from_file_location(
    "frontend", os.path.join(os.path.dirname(os.path.dirname(__file__)), "frontend.py")
)
_frontend_module = importlib.util.module_from_spec(_frontend_spec)
sys.modules["frontend"] = _frontend_module
_frontend_spec.loader.exec_module(_frontend_module)
import frontend  # pylint: disable=wrong-import-position
from frontend import create_app  # pylint: disable=wrong-import-position

from tests.constants import (
    EXAMPLE_ACCOUNT,
    EXAMPLE_BALANCE,
    EXAMPLE_CONTACTS,
    EXAMPLE_PUBLIC_KEY,
    EXAMPLE_TOKEN,
    EXAMPLE_TRANSACTIONS,
    EXPIRED_TOKEN,
    FOREIGN_TOKEN,
)


BASE_ENV = {
    "VERSION": "9.1",
    "ENABLE_TRACING": "false",
    "LOCAL_ROUTING_NUM": "123456789",
    "TRANSACTIONS_API_ADDR": "ledgerwriter:8080",
    "USERSERVICE_API_ADDR": "userservice:8080",
    "BALANCES_API_ADDR": "balancereader:8080",
    "HISTORY_API_ADDR": "transactionhistory:8080",
    "CONTACTS_API_ADDR": "contacts:8080",
    "SCHEME": "http",
    "PUB_KEY_PATH": "/test/public.pem",
    "CLUSTER_NAME": "test-cluster",
    "POD_ZONE": "test-zone",
}


def _response(status_code=200, json_value=None, text=""):
    response = Mock()
    response.status_code = status_code
    response.text = text
    response.ok = status_code < 400
    response.json.return_value = json_value
    return response


def _create_app(env_overrides=None, metadata_ok=False):
    env = {**BASE_ENV, **(env_overrides or {})}
    if metadata_ok:
        metadata = [
            _response(text="metadata-cluster"),
            _response(text="projects/1/zones/metadata-zone"),
        ]
    else:
        metadata = [RequestException("metadata unavailable")] * 2
    with patch("os.environ", env), patch(
        "frontend.open", mock_open(read_data=EXAMPLE_PUBLIC_KEY.decode())
    ), patch("frontend.requests.get", side_effect=metadata):
        app = create_app()
    app.config["TESTING"] = True
    app.config["PUBLIC_KEY"] = EXAMPLE_PUBLIC_KEY.decode()
    return app, app.test_client()


class TestFrontend(unittest.TestCase):
    """Endpoint and application-factory tests."""

    def test_probe_endpoints_and_root(self):
        app, client = _create_app(metadata_ok=True)
        with patch("os.environ", BASE_ENV):
            response = client.get("/version")
        self.assertEqual((response.status_code, response.text), (200, "9.1"))
        self.assertEqual(client.get("/ready").text, "ok")
        where = client.get("/whereami").text
        self.assertIn("metadata-cluster", where)
        self.assertIn("metadata-zone", where)
        self.assertIn("Pod:", where)
        self.assertIn("SIGN IN", client.get("/").text)
        client.set_cookie("token", EXAMPLE_TOKEN)
        with patch("api_call.get", return_value=None):
            response = client.get("/")
        self.assertEqual(response.status_code, 200)
        self.assertIn("Jane Doe", response.text)
        self.assertIsNotNone(app)

    def test_home_rejects_missing_expired_and_foreign_tokens(self):
        _, client = _create_app()
        for token in (None, EXPIRED_TOKEN, FOREIGN_TOKEN):
            if token is None:
                response = client.get("/home")
            else:
                client.set_cookie("token", token)
                response = client.get("/home")
            self.assertEqual(response.status_code, 302)
            self.assertIn("/login", response.location)

    def test_home_renders_api_data_and_contact_labels(self):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)

        def get_api(**kwargs):
            if "/balances/" in kwargs["url"]:
                return _response(json_value=EXAMPLE_BALANCE)
            if "/transactions/" in kwargs["url"]:
                return _response(json_value=EXAMPLE_TRANSACTIONS)
            return _response(json_value=EXAMPLE_CONTACTS)

        with patch("api_call.get", side_effect=get_api):
            response = client.get("/home?msg=Loaded")
        self.assertEqual(response.status_code, 200)
        self.assertIn("Jane Doe", response.text)
        self.assertIn("Savings", response.text)
        self.assertNotIn("No Transactions Found", response.text)
        self.assertIn("$1,234.56", response.text)

        # Exercise both transaction directions and the missing-contact label.
        self.assertIn("Credit", response.text)
        self.assertIn("Debit", response.text)
        self.assertIn("None", response.text)

    def test_home_uses_default_values_when_api_calls_fail(self):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        with patch("api_call.get", return_value=None):
            response = client.get("/home")
        self.assertEqual(response.status_code, 200)
        self.assertIn("Error: Could Not Load Transactions", response.text)
        self.assertIn("$---", response.text)

    def test_payment_requires_authentication(self):
        _, client = _create_app()
        self.assertEqual(client.post("/payment").status_code, 401)
        client.set_cookie("token", FOREIGN_TOKEN)
        self.assertEqual(client.post("/payment").status_code, 401)

    @patch("frontend.sleep")
    @patch("frontend.requests.post")
    def test_payment_happy_path_and_new_contact(self, post, _sleep):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        post.return_value = _response(201)
        response = client.post(
            "/payment",
            data={
                "account_num": "add",
                "contact_account_num": "7777777777",
                "contact_label": "New friend",
                "amount": "12.34",
                "uuid": "payment-id",
            },
        )
        self.assertEqual(response.status_code, 303)
        self.assertIn("Payment successful", unquote_plus(response.location))
        self.assertEqual(post.call_count, 2)
        contact_call = post.call_args_list[0].kwargs
        transaction_call = post.call_args_list[1].kwargs
        self.assertEqual(json.loads(contact_call["data"])["label"], "New friend")
        transaction = json.loads(transaction_call["data"])
        self.assertEqual(transaction["fromAccountNum"], EXAMPLE_ACCOUNT)
        self.assertEqual(transaction["toAccountNum"], "7777777777")
        self.assertEqual(transaction["amount"], 1234)

    @patch("frontend.requests.post")
    def test_payment_contact_failure_is_reported(self, post):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        failed = _response(400, text="contact failed")
        failed.raise_for_status.side_effect = HTTPError("bad contact")
        post.return_value = failed
        response = client.post(
            "/payment",
            data={
                "account_num": "add",
                "contact_account_num": "7777777777",
                "contact_label": "New friend",
                "amount": "1",
                "uuid": "id",
            },
        )
        self.assertIn("Payment failed: contact failed", unquote_plus(response.location))
        post.assert_called_once()

    @patch("frontend.sleep")
    @patch("frontend.requests.post")
    def test_payment_transaction_failure_and_invalid_amount(self, post, _sleep):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        failed = _response(400, text="ledger failed")
        failed.raise_for_status.side_effect = HTTPError("bad transaction")
        post.return_value = failed
        response = client.post(
            "/payment",
            data={
                "account_num": "7777777777",
                "amount": "1",
                "uuid": "id",
            },
        )
        self.assertIn("Payment failed: ledger failed", unquote_plus(response.location))
        response = client.post(
            "/payment",
            data={"account_num": "7777777777", "amount": "abc", "uuid": "id"},
        )
        self.assertIn("Payment failed", unquote_plus(response.location))

        post.side_effect = RequestException("offline")
        response = client.post(
            "/payment",
            data={"account_num": "7777777777", "amount": "1", "uuid": "id"},
        )
        self.assertIn("Payment failed", unquote_plus(response.location))

    def test_deposit_requires_authentication(self):
        _, client = _create_app()
        self.assertEqual(client.post("/deposit").status_code, 401)

    @patch("frontend.sleep")
    @patch("frontend.requests.post")
    def test_deposit_existing_account_happy_path(self, post, _sleep):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        post.return_value = _response(201)
        response = client.post(
            "/deposit",
            data={
                "account": json.dumps(
                    {"account_num": "7777777777", "routing_num": "987654321"}
                ),
                "amount": "2.50",
                "uuid": "deposit-id",
            },
        )
        self.assertEqual(response.status_code, 303)
        self.assertIn("Deposit successful", unquote_plus(response.location))
        transaction = json.loads(post.call_args.kwargs["data"])
        self.assertEqual(transaction["fromAccountNum"], "7777777777")
        self.assertEqual(transaction["amount"], 250)

    @patch("frontend.sleep")
    @patch("frontend.requests.post")
    def test_deposit_new_external_account(self, post, _sleep):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        post.return_value = _response(201)
        response = client.post(
            "/deposit",
            data={
                "account": "add",
                "external_account_num": "7777777777",
                "external_routing_num": "987654321",
                "external_label": "External",
                "amount": "2",
                "uuid": "deposit-id",
            },
        )
        self.assertIn("Deposit successful", unquote_plus(response.location))
        self.assertEqual(post.call_count, 2)
        self.assertTrue(json.loads(post.call_args_list[0].kwargs["data"])["is_external"])

        response = client.post(
            "/deposit",
            data={
                "account": "add",
                "external_account_num": "7777777777",
                "external_routing_num": "123456789",
                "amount": "2",
                "uuid": "deposit-id",
            },
        )
        self.assertIn("invalid routing number", unquote_plus(response.location))

    @patch("frontend.requests.post", side_effect=RequestException("offline"))
    def test_deposit_request_failure(self, _post):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        response = client.post(
            "/deposit",
            data={
                "account": json.dumps(
                    {"account_num": "7777777777", "routing_num": "987654321"}
                ),
                "amount": "2",
                "uuid": "deposit-id",
            },
        )
        self.assertIn("Deposit failed", unquote_plus(response.location))

    def test_login_get_branches(self):
        _, client = _create_app()
        self.assertIn("SIGN IN", client.get("/login").text)
        client.set_cookie("token", EXAMPLE_TOKEN)
        self.assertIn("/home", client.get("/login").location)

        env = {
            "REGISTERED_OAUTH_CLIENT_ID": "client",
            "ALLOWED_OAUTH_REDIRECT_URI": "https://app/callback",
        }
        _, client = _create_app(env)
        with patch("os.environ", {**BASE_ENV, **env}):
            query = "response_type=code&client_id=wrong&redirect_uri=https://app/callback"
            self.assertIn(
                "Invalid client_id",
                unquote_plus(client.get("/login?" + query).location),
            )
            query = "response_type=code&client_id=client&redirect_uri=https://wrong"
            self.assertIn(
                "Invalid redirect_uri",
                unquote_plus(client.get("/login?" + query).location),
            )
            client.set_cookie("token", EXAMPLE_TOKEN)
            query = (
                "response_type=code&client_id=client&redirect_uri=https://app/callback"
                "&state=state&app_name=App"
            )
            self.assertIn("/consent", client.get("/login?" + query).location)
            client.delete_cookie("token")
            self.assertIn("SIGN IN", client.get("/login?" + query).text)

    @patch("frontend.requests.get")
    def test_login_post_success_and_oauth_redirect(self, get):
        _, client = _create_app()
        get.return_value = _response(json_value={"token": EXAMPLE_TOKEN})
        response = client.post(
            "/login", data={"username": "jdoe", "password": "pw"}
        )
        self.assertIn("/home", response.location)
        self.assertIn("Max-Age=3600", response.headers["Set-Cookie"])
        get.assert_called_once()

        oauth_args = (
            "?response_type=code&state=state&redirect_uri=https://app/callback"
            "&app_name=App"
        )
        response = client.post(
            "/login" + oauth_args, data={"username": "jdoe", "password": "pw"}
        )
        self.assertIn("/consent", response.location)

    @patch("frontend.requests.get")
    def test_login_post_failures(self, get):
        _, client = _create_app()
        response = _response()
        response.raise_for_status.side_effect = HTTPError("bad login")
        get.return_value = response
        self.assertIn("Login Failed", unquote_plus(client.post(
            "/login", data={"username": "jdoe", "password": "pw"}
        ).location))
        get.side_effect = RequestException("offline")
        self.assertIn("Login Failed", unquote_plus(client.post(
            "/login", data={"username": "jdoe", "password": "pw"}
        ).location))

    @patch("frontend.requests.post")
    def test_consent_get_and_callback_branches(self, post):
        env = {
            "REGISTERED_OAUTH_CLIENT_ID": "client",
            "ALLOWED_OAUTH_REDIRECT_URI": "https://app/callback",
        }
        _, client = _create_app(env)
        args = "response_type=code&state=state&redirect_uri=https://app/callback&app_name=App"
        self.assertIn("/login", client.get("/consent?" + args).location)
        client.set_cookie("token", EXAMPLE_TOKEN)
        self.assertIn("Do you consent?", client.get("/consent?" + args).text)

        post.return_value = _response(302)
        post.return_value.headers = {"Location": "https://app/done"}
        client.set_cookie("consented", "true")
        self.assertEqual(client.get("/consent?" + args).location, "https://app/done")
        post.return_value = _response(200)
        self.assertIn("#error=server_error", client.get("/consent?" + args).location)
        post.side_effect = RequestException("callback offline")
        self.assertIn("#error=server_error", client.get("/consent?" + args).location)

    @patch("frontend.requests.post")
    def test_consent_post_approve_and_deny(self, post):
        _, client = _create_app()
        client.set_cookie("token", EXAMPLE_TOKEN)
        post.return_value = _response(302)
        post.return_value.headers = {"Location": "https://app/done"}
        query = "?consent=true&state=state&redirect_uri=https://app/callback"
        response = client.post("/consent" + query)
        self.assertEqual(response.location, "https://app/done")
        self.assertIn("consented=true", response.headers["Set-Cookie"])
        query = "?consent=false&state=state&redirect_uri=https://app/callback"
        self.assertIn("#error=access_denied", client.post("/consent" + query).location)

    @patch("frontend.requests.get")
    @patch("frontend.requests.post")
    def test_signup_page_and_submission(self, post, get):
        _, client = _create_app()
        self.assertIn("Register a new account", client.get("/signup").text)
        client.set_cookie("token", EXAMPLE_TOKEN)
        self.assertIn("/home", client.get("/signup").location)
        client.delete_cookie("token")
        post.return_value = _response(201)
        get.return_value = _response(json_value={"token": EXAMPLE_TOKEN})
        response = client.post(
            "/signup", data={"username": "newuser", "password": "pw"}
        )
        self.assertIn("/home", response.location)
        self.assertIn("Max-Age=3600", response.headers["Set-Cookie"])
        post.return_value = _response(400)
        self.assertIn(
            "Account creation failed",
            unquote_plus(client.post(
                "/signup", data={"username": "newuser", "password": "pw"}
            ).location),
        )
        post.side_effect = RequestException("offline")
        self.assertIn(
            "Account creation failed",
            unquote_plus(client.post(
                "/signup", data={"username": "newuser", "password": "pw"}
            ).location),
        )

    def test_logout_deletes_authentication_cookies(self):
        _, client = _create_app()
        response = client.post("/logout")
        self.assertIn("/login", response.location)
        cookies = response.headers.getlist("Set-Cookie")
        self.assertTrue(any("token=;" in cookie and "Max-Age=0" in cookie for cookie in cookies))
        self.assertTrue(any("consented=;" in cookie and "Max-Age=0" in cookie for cookie in cookies))

    def test_template_formatters(self):
        app, _ = _create_app()
        currency = app.jinja_env.globals["format_currency"]
        self.assertEqual(currency(None), "$---")
        self.assertEqual(currency(123456), "$1,234.56")
        self.assertEqual(currency(-1234), "-$12.34")
        day = app.jinja_env.globals["format_timestamp_day"]
        month = app.jinja_env.globals["format_timestamp_month"]
        timestamp = "2024-01-02T03:04:05.000000+0000"
        self.assertEqual(day(timestamp), "02")
        self.assertEqual(month(timestamp), "Jan")

    def test_platform_configuration(self):
        expected = {
            "alibaba": "Alibaba Cloud",
            "aws": "AWS",
            "azure": "Azure",
            "gcp": "Google Cloud",
            "local": "Local",
            "onprem": "On-Premises",
        }
        for platform, display in expected.items():
            _, client = _create_app({"ENV_PLATFORM": platform})
            self.assertIn(display, client.get("/login").text)
        _, client = _create_app({"ENV_PLATFORM": "unsupported"})
        self.assertNotIn("unsupported", client.get("/login").text)
        _, client = _create_app()
        self.assertIn("SIGN IN", client.get("/login").text)

    def test_tracing_enabled_initializes_instrumentation(self):
        trace = MagicMock()
        provider = MagicMock()
        exporter = MagicMock()
        flask_instrumentor = MagicMock()
        requests_instrumentor = MagicMock()
        jinja_instrumentor = MagicMock()
        set_global_textmap = MagicMock()
        with patch.multiple(
            "frontend",
            CloudTraceSpanExporter=exporter,
            TracerProvider=MagicMock(return_value=provider),
            FlaskInstrumentor=flask_instrumentor,
            RequestsInstrumentor=requests_instrumentor,
            Jinja2Instrumentor=jinja_instrumentor,
            trace=trace,
            set_global_textmap=set_global_textmap,
        ):
            _create_app({"ENABLE_TRACING": "true"})
        trace.set_tracer_provider.assert_called_once_with(provider)
        flask_instrumentor.return_value.instrument_app.assert_called_once()
        requests_instrumentor.return_value.instrument.assert_called_once()
        jinja_instrumentor.return_value.instrument.assert_called_once()
