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

"""Tests for traced thread-pool execution."""

import unittest
from unittest.mock import patch

from traced_thread_pool_executor import TracedThreadPoolExecutor


class TestTracedThreadPoolExecutor(unittest.TestCase):
    """Tests for context propagation and ordinary submission."""

    def test_init_stores_tracer_and_constructs_pool(self):
        tracer = object()
        executor = TracedThreadPoolExecutor(tracer, max_workers=1)
        try:
            self.assertIs(executor.tracer, tracer)
            self.assertEqual(executor.submit(lambda: 4).result(), 4)
        finally:
            executor.shutdown()

    @patch("traced_thread_pool_executor.otel_context.attach")
    @patch("traced_thread_pool_executor.otel_context.get_current")
    def test_submit_attaches_current_context(self, get_current, attach):
        get_current.return_value = {"span": "current"}
        executor = TracedThreadPoolExecutor(object(), max_workers=1)
        try:
            self.assertEqual(executor.submit(lambda x, y=0: x + y, 2, y=3).result(), 5)
            attach.assert_called_once_with({"span": "current"})
        finally:
            executor.shutdown()

    @patch("traced_thread_pool_executor.otel_context.get_current", return_value=None)
    def test_submit_without_context_forwards_args_and_kwargs(self, _get_current):
        executor = TracedThreadPoolExecutor(object(), max_workers=1)
        try:
            self.assertEqual(executor.submit(lambda x, y: x * y, 4, y=5).result(), 20)
        finally:
            executor.shutdown()

    @patch("traced_thread_pool_executor.otel_context.attach")
    def test_with_otel_context_attaches_and_calls_function(self, attach):
        executor = TracedThreadPoolExecutor(object(), max_workers=1)
        try:
            function = lambda: "done"
            self.assertEqual(executor.with_otel_context("ctx", function), "done")
            attach.assert_called_once_with("ctx")
        finally:
            executor.shutdown()
