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

"""Tests for frontend API calls."""

import unittest
from unittest.mock import MagicMock, patch

from requests.exceptions import RequestException

from api_call import ApiCall, ApiRequest


class TestApiCall(unittest.TestCase):
    """Tests for ApiRequest and ApiCall."""

    def test_api_request_stores_request_values(self):
        request = ApiRequest("https://example.test", {"X-Test": "yes"}, 7)
        self.assertEqual(request.url, "https://example.test")
        self.assertEqual(request.headers, {"X-Test": "yes"})
        self.assertEqual(request.timeout, 7)

    def test_api_call_stores_values(self):
        request = ApiRequest("url", {}, 1)
        logger = MagicMock()
        call = ApiCall("balance", request, logger)
        self.assertEqual(call.display_name, "balance")
        self.assertIs(call.api_request, request)
        self.assertIs(call.logger, logger)

    @patch("api_call.get")
    def test_make_call_success(self, mocked_get):
        response = MagicMock()
        request = ApiRequest("url", {"Authorization": "token"}, 3)
        call = ApiCall("balance", request, MagicMock())
        mocked_get.return_value = response
        self.assertIs(call.make_call(), response)
        mocked_get.assert_called_once_with(
            url="url", headers={"Authorization": "token"}, timeout=3
        )

    @patch("api_call.get")
    def test_make_call_request_exception(self, mocked_get):
        logger = MagicMock()
        mocked_get.side_effect = RequestException("offline")
        call = ApiCall("history", ApiRequest("url", {}, 3), logger)
        self.assertIsNone(call.make_call())
        logger.error.assert_called_once()

    @patch("api_call.get")
    def test_make_call_value_error(self, mocked_get):
        logger = MagicMock()
        mocked_get.side_effect = ValueError("bad URL")
        call = ApiCall("contacts", ApiRequest("url", {}, 3), logger)
        self.assertIsNone(call.make_call())
        logger.error.assert_called_once()
