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

"""
Tests for the locust load generator tasks
"""

import json
import string
import unittest
from unittest.mock import MagicMock

from locust.exception import InterruptTaskSet

import locustfile
from locustfile import (
    MASTER_PASSWORD,
    TRANSACTION_ACCT_LIST,
    AllTasks,
    WebsiteUser,
    generate_username,
    signup_helper,
)


def make_history_entry(status_code=200, token=None):
    """Create a fake redirect entry for a response history"""
    entry = MagicMock()
    entry.status_code = status_code
    entry.cookies.get.return_value = token
    return entry


def make_response(history=(), url="http://frontend/home"):
    """Create a fake catch_response response"""
    response = MagicMock()
    response.history = list(history)
    response.url = url
    return response


def bind_response(mocked_call, response):
    """Make a mocked client call return the response as a context manager"""
    mocked_call.return_value.__enter__.return_value = response


def make_taskset(taskset_class, username="testuser"):
    """Instantiate a TaskSet with a mocked user and HTTP client"""
    user = MagicMock()
    user.username = username
    user.client = MagicMock()
    return taskset_class(user)


class TestHelpers(unittest.TestCase):
    """
    Test cases for the module level helpers
    """

    def test_generate_username_returns_random_alphanumeric_name(self):
        """test usernames are 15 alphanumeric characters and not repeated"""
        allowed = set(string.ascii_letters + string.digits)
        names = {generate_username() for _ in range(100)}
        # assert random enough to not collide within 100 draws
        self.assertEqual(100, len(names))
        for name in names:
            self.assertEqual(15, len(name))
            self.assertTrue(set(name).issubset(allowed))

    def test_transaction_accounts_are_valid_account_numbers(self):
        """test the pre-generated accounts are 10 digit numbers"""
        self.assertEqual(50, len(TRANSACTION_ACCT_LIST))
        for account in TRANSACTION_ACCT_LIST:
            self.assertEqual(10, len(account))
            self.assertTrue(account.startswith("11111"))

    def test_signup_helper_returns_true_when_token_cookie_returned(self):
        """test a signup redirect carrying a token counts as success"""
        locust = MagicMock()
        response = make_response(
            history=[make_history_entry(token=None), make_history_entry(token="jwt")]
        )
        bind_response(locust.client.post, response)

        self.assertTrue(signup_helper(locust, "jdoe"))

        response.success.assert_called_once()
        response.failure.assert_not_called()
        _, kwargs = locust.client.post.call_args
        self.assertEqual(("/signup",), locust.client.post.call_args[0])
        self.assertTrue(kwargs["catch_response"])
        userdata = kwargs["data"]
        self.assertEqual("jdoe", userdata["username"])
        self.assertEqual("jdoe", userdata["firstname"])
        self.assertEqual(MASTER_PASSWORD, userdata["password"])
        self.assertEqual(MASTER_PASSWORD, userdata["password-repeat"])

    def test_signup_helper_returns_false_when_no_token_cookie(self):
        """test a signup whose redirects carry no token fails"""
        locust = MagicMock()
        response = make_response(history=[make_history_entry(token=None)])
        bind_response(locust.client.post, response)

        self.assertFalse(signup_helper(locust, "jdoe"))

        response.failure.assert_called_once_with("login failed")
        response.success.assert_not_called()

    def test_signup_helper_returns_false_when_no_redirects(self):
        """test a signup without any redirect fails"""
        locust = MagicMock()
        response = make_response()
        bind_response(locust.client.post, response)

        self.assertFalse(signup_helper(locust, "jdoe"))

        response.failure.assert_called_once_with("login failed")


class TestUnauthenticatedTasks(unittest.TestCase):
    """
    Test cases for the tasks run before a token is obtained
    """

    def setUp(self):
        """Create a task set with a mocked client"""
        self.tasks = make_taskset(AllTasks.UnauthenticatedTasks)
        self.client = self.tasks.user.client

    def test_view_login_succeeds_without_redirect(self):
        """test loading /login while logged out does not fail"""
        response = make_response()
        bind_response(self.client.get, response)

        self.tasks.view_login()

        self.client.get.assert_called_once_with("/login", catch_response=True)
        response.failure.assert_not_called()

    def test_view_login_fails_on_redirect_to_home(self):
        """test loading /login while logged on fails"""
        response = make_response(history=[make_history_entry(status_code=302)])
        bind_response(self.client.get, response)

        self.tasks.view_login()

        response.failure.assert_called_once_with("Logged on: Got redirect to /home")

    def test_view_login_ignores_non_redirect_history(self):
        """test a 200 in the response history is not treated as a redirect"""
        response = make_response(history=[make_history_entry(status_code=200)])
        bind_response(self.client.get, response)

        self.tasks.view_login()

        response.failure.assert_not_called()

    def test_view_signup_succeeds_without_redirect(self):
        """test loading /signup while logged out does not fail"""
        response = make_response()
        bind_response(self.client.get, response)

        self.tasks.view_signup()

        self.client.get.assert_called_once_with("/signup", catch_response=True)
        response.failure.assert_not_called()

    def test_view_signup_fails_on_redirect_to_home(self):
        """test loading /signup while logged on fails"""
        response = make_response(history=[make_history_entry(status_code=399)])
        bind_response(self.client.get, response)

        self.tasks.view_signup()

        response.failure.assert_called_once_with("Logged on: Got redirect to /home")

    def test_signup_stores_username_and_interrupts_on_success(self):
        """test a successful signup hands over to the authenticated tasks"""
        response = make_response(history=[make_history_entry(token="jwt")])
        bind_response(self.client.post, response)

        with self.assertRaises(InterruptTaskSet):
            self.tasks.signup()

        userdata = self.client.post.call_args[1]["data"]
        self.assertEqual(userdata["username"], self.tasks.user.username)
        self.assertEqual(15, len(self.tasks.user.username))

    def test_signup_keeps_running_unauthenticated_on_failure(self):
        """test a failed signup does not interrupt the task set"""
        response = make_response(history=[make_history_entry(token=None)])
        bind_response(self.client.post, response)
        self.tasks.user.username = None

        self.tasks.signup()

        self.assertIsNone(self.tasks.user.username)
        response.failure.assert_called_once_with("login failed")


class TestAuthenticatedTasks(unittest.TestCase):
    """
    Test cases for the tasks run once a token is held
    """

    def setUp(self):
        """Create a task set with a mocked client"""
        self.tasks = make_taskset(AllTasks.AuthenticatedTasks)
        self.client = self.tasks.user.client

    def test_on_start_deposits_large_starting_balance(self):
        """test a big deposit is made so later payments are covered"""
        bind_response(self.client.post, make_response())

        self.tasks.on_start()

        args, kwargs = self.client.post.call_args
        self.assertEqual(("/deposit",), args)
        self.assertEqual(1000000, kwargs["data"]["amount"])

    def test_view_index_succeeds_without_redirect(self):
        """test loading / while logged on does not fail"""
        response = make_response()
        bind_response(self.client.get, response)

        self.tasks.view_index()

        self.client.get.assert_called_once_with("/", catch_response=True)
        response.failure.assert_not_called()

    def test_view_index_fails_on_redirect_to_login(self):
        """test loading / while logged out fails"""
        response = make_response(history=[make_history_entry(status_code=302)])
        bind_response(self.client.get, response)

        self.tasks.view_index()

        response.failure.assert_called_once_with(
            "Not logged on: Got redirect to /login"
        )

    def test_view_home_succeeds_without_redirect(self):
        """test loading /home while logged on does not fail"""
        response = make_response()
        bind_response(self.client.get, response)

        self.tasks.view_home()

        self.client.get.assert_called_once_with("/home", catch_response=True)
        response.failure.assert_not_called()

    def test_view_home_fails_on_redirect_to_login(self):
        """test loading /home while logged out fails"""
        response = make_response(history=[make_history_entry(status_code=303)])
        bind_response(self.client.get, response)

        self.tasks.view_home()

        response.failure.assert_called_once_with(
            "Not logged on: Got redirect to /login"
        )

    def test_payment_posts_random_amount_to_known_account(self):
        """test a payment without an explicit amount uses a random one"""
        response = make_response()
        bind_response(self.client.post, response)

        self.tasks.payment()

        args, kwargs = self.client.post.call_args
        self.assertEqual(("/payment",), args)
        self.assertTrue(kwargs["catch_response"])
        transaction = kwargs["data"]
        self.assertIn(transaction["account_num"], TRANSACTION_ACCT_LIST)
        self.assertGreaterEqual(transaction["amount"], 0)
        self.assertLess(transaction["amount"], 1000)
        self.assertEqual(15, len(transaction["uuid"]))
        response.failure.assert_not_called()

    def test_payment_uses_given_amount(self):
        """test an explicit payment amount is passed through"""
        bind_response(self.client.post, make_response())

        self.tasks.payment(42)

        self.assertEqual(42, self.client.post.call_args[1]["data"]["amount"])

    def test_payment_fails_when_redirected_to_failure_page(self):
        """test a payment redirected to a failure page is reported failed"""
        response = make_response(url="http://frontend/home?msg=payment+failed")
        bind_response(self.client.post, response)

        self.tasks.payment(10)

        response.failure.assert_called_once_with("payment failed")

    def test_payment_fails_when_no_url(self):
        """test a payment without a response url is reported failed"""
        response = make_response(url=None)
        bind_response(self.client.post, response)

        self.tasks.payment(10)

        response.failure.assert_called_once_with("payment failed")

    def test_deposit_posts_external_account_and_random_amount(self):
        """test a deposit without an explicit amount uses a random one"""
        response = make_response()
        bind_response(self.client.post, response)

        self.tasks.deposit()

        args, kwargs = self.client.post.call_args
        self.assertEqual(("/deposit",), args)
        transaction = kwargs["data"]
        acct_info = json.loads(transaction["account"])
        self.assertIn(acct_info["account_num"], TRANSACTION_ACCT_LIST)
        self.assertEqual("111111111", acct_info["routing_num"])
        self.assertGreaterEqual(transaction["amount"], 0)
        self.assertLess(transaction["amount"], 1000)
        response.failure.assert_not_called()

    def test_deposit_fails_when_redirected_to_failure_page(self):
        """test a deposit redirected to a failure page is reported failed"""
        response = make_response(url="http://frontend/home?msg=deposit+failed")
        bind_response(self.client.post, response)

        self.tasks.deposit(10)

        response.failure.assert_called_once_with("deposit failed")

    def test_deposit_fails_when_no_url(self):
        """test a deposit without a response url is reported failed"""
        response = make_response(url=None)
        bind_response(self.client.post, response)

        self.tasks.deposit(10)

        response.failure.assert_called_once_with("deposit failed")

    def test_login_succeeds_when_token_cookie_returned(self):
        """test a login redirect carrying a token counts as success"""
        response = make_response(
            history=[make_history_entry(token=None), make_history_entry(token="jwt")]
        )
        bind_response(self.client.post, response)

        self.tasks.login()

        response.success.assert_called_once()
        response.failure.assert_not_called()
        args, _ = self.client.post.call_args
        self.assertEqual("/login", args[0])
        self.assertEqual(
            {"username": self.tasks.user.username, "password": MASTER_PASSWORD},
            args[1],
        )

    def test_login_fails_without_token_cookie(self):
        """test a login whose redirects carry no token fails"""
        response = make_response(history=[make_history_entry(token=None)])
        bind_response(self.client.post, response)

        self.tasks.login()

        response.failure.assert_called_once_with("login failed")
        response.success.assert_not_called()

    def test_logout_clears_username_and_interrupts(self):
        """test a successful logout hands back to the unauthenticated tasks"""
        response = make_response(history=[make_history_entry(status_code=302)])
        bind_response(self.client.post, response)

        with self.assertRaises(InterruptTaskSet):
            self.tasks.logout()

        self.client.post.assert_called_once_with("/logout", catch_response=True)
        response.success.assert_called_once()
        self.assertIsNone(self.tasks.user.username)

    def test_logout_without_redirect_stays_logged_on(self):
        """test a logout that does not redirect keeps the session"""
        response = make_response(history=[make_history_entry(status_code=200)])
        bind_response(self.client.post, response)

        self.tasks.logout()

        response.success.assert_not_called()
        self.assertEqual("testuser", self.tasks.user.username)


class TestWebsiteUser(unittest.TestCase):
    """
    Test cases for the locust user definition
    """

    def test_user_runs_all_tasks_with_one_second_wait(self):
        """test the user is wired up to the task sets"""
        self.assertEqual([AllTasks], WebsiteUser.tasks)
        self.assertEqual(1, WebsiteUser.wait_time(MagicMock()))

    def test_all_tasks_runs_unauthenticated_then_authenticated(self):
        """test the sequential task set order"""
        self.assertEqual(
            [AllTasks.UnauthenticatedTasks, AllTasks.AuthenticatedTasks],
            locustfile.AllTasks.tasks,
        )
