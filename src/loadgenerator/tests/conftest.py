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
Test setup for the load generator.

Importing locust triggers gevent's monkey patching, which aborts the
interpreter on this platform (the prebuilt gevent wheel for Python 3.14 fails a
CPython unicode assertion). The load generator's task functions never need the
cooperative networking stack, so patching is disabled before locust is
imported.
"""

from gevent import monkey


def _no_patch(*_args, **_kwargs):
    """Skip gevent monkey patching under test"""


monkey.patch_all = _no_patch
