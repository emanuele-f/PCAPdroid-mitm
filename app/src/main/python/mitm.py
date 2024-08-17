#!/usr/bin/env python3
#
#  This file is part of PCAPdroid.
#
#  PCAPdroid is free software: you can redistribute it and/or modify
#  it under the terms of the GNU General Public License as published by
#  the Free Software Foundation, either version 3 of the License, or
#  (at your option) any later version.
#
#  PCAPdroid is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU General Public License for more details.
#
#  You should have received a copy of the GNU General Public License
#  along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
#
#  Copyright 2023 - Emanuele Faranda
#

import os

MITMPROXY_CONF_DIR = os.environ["HOME"] + "/.mitmproxy"
USER_ADDONS_DIR = os.environ["HOME"] + "/mitmproxy-addons"
CA_CERT_PATH = MITMPROXY_CONF_DIR + "/mitmproxy-ca-cert.cer"

from mitmproxy import options
from mitmproxy.tools import dump, cmdline
from mitmproxy.tools.main import mitmdump, process_options
from mitmproxy.certs import CertStore, Cert
from mitmproxy.proxy import server_hooks
from mitmproxy.proxy.events import OpenConnectionCompleted
from pcapdroid import PCAPdroid, AddonOpts
from js_injector import JsInjector
from pathlib import Path
import mitmproxy
import traceback
import socket
import asyncio
import sys
import importlib

master = None
pcapdroid = None
js_injector = None
running = False

orig_stdout = sys.stdout
class StdOut:
    def isatty(self):
        return orig_stdout.isatty()
    def write(self, msg):
        if pcapdroid:
            pcapdroid.log(msg)

orig_stderr = sys.stderr
class StdErr:
    def isatty(self):
        return orig_stderr.isatty()
    def write(self, msg):
        if pcapdroid:
            pcapdroid.log_warn(msg)
    def flush(self):
        pass

sys.stdout = StdOut()
sys.stderr = StdErr()

# no extra newline in logcat
import builtins
builtins.print = lambda x, *args, **kargs: sys.stdout.write(str(x))

# Temporary hack to provide a server_error hook
ConnectionHandler = mitmproxy.proxy.server.ConnectionHandler
orig_server_event = ConnectionHandler.server_event

def server_event_proxy(handler, event):
    if pcapdroid and isinstance(event, OpenConnectionCompleted) and event.command.connection:
        conn = event.command.connection
        if conn.error:
            hook_data = server_hooks.ServerConnectionHookData(
                client=handler.client,
                server=conn
            )
            pcapdroid.server_error(hook_data)
    return orig_server_event(handler, event)

def load_addon(modname, addons):
    try:
        existing_module = modname in sys.modules

        m = importlib.import_module(modname)
        if not m:
            return

        if existing_module:
            # reload the module if already loaded in a previous execution
            importlib.reload(m)

        if hasattr(m, "addons") and isinstance(m.addons, list):
            for addon in m.addons:
                addons.add(addon)
    except Exception:
        sys.stderr.write("Failed to load addon \"" + modname + "\"")
        sys.stderr.write(traceback.format_exc())

def jarray_to_set(arr):
    rv = set()
    for elem in arr:
        rv.add(elem)
    return rv

# Entrypoint: runs mitmproxy
# From mitmproxy.tools.main.run, without the signal handlers
def run(fd: int, jenabled_addons, dump_client: bool, dump_keylog: bool,
        short_payload: bool, mitm_args: str):
    global master
    global running
    global pcapdroid, js_injector
    running = True

    try:
        with socket.fromfd(fd, socket.AF_INET, socket.SOCK_STREAM) as sock:
            async def main():
                global master
                global pcapdroid, js_injector
                opts = options.Options()
                master = dump.DumpMaster(opts)

                # instantiate PCAPdroid early to send error log via the API
                pcapdroid = PCAPdroid(sock, AddonOpts(dump_client, dump_keylog, short_payload))

                enabled_addons = jarray_to_set(jenabled_addons)

                # Load addons (order is important)
                master.addons.add(pcapdroid)

                # JsInjector addon
                if "Js Injector" in enabled_addons:
                    js_injector = JsInjector()
                    master.addons.add(js_injector)

                if os.path.exists(USER_ADDONS_DIR):
                    sys.path.append(USER_ADDONS_DIR)
                    importlib.invalidate_caches()

                    for f in os.listdir(USER_ADDONS_DIR):
                        if f.endswith(".py"):
                            fname = f[:-3]

                            if fname in enabled_addons:
                                print("Loading user addon: " + f)
                                load_addon(fname, master.addons)

                print("mitmdump " + mitm_args)
                parser = cmdline.mitmdump(opts)
                args = parser.parse_args(mitm_args.split())
                process_options(parser, opts, args)
                checkCertificate()

                ConnectionHandler.server_event = lambda handler, ev: server_event_proxy(handler, ev)

                print("Running mitmdump...")
                await master.run()

                # The proxyserver is not stopped by master.shutdown. Must be
                # stopped to properly close the TCP socket.
                proxyserver = master.addons.lookup.get("proxyserver")
                if proxyserver:
                    # see test_proxyserver.py
                    print("Stopping proxyserver...")
                    master.options.update(server=False)
                    await proxyserver.setup_servers()

            asyncio.run(main())
    except Exception:
        print(traceback.format_exc())

    print("mitmdump stopped")
    master = None
    running = False
    pcapdroid = None
    js_injector = None

# Entrypoint: stops the running mitmproxy
def stop():
    global running

    if not running:
        return

    print("Stopping mitmdump...")
    running = False

    if master:
        master.shutdown()

# Entrypoint: logs a message to console/PCAPdroid
def log(lvl: int, msg: str):
    if pcapdroid:
        pcapdroid.do_log(msg, lvl)

def checkCertificate():
    if os.path.exists(CA_CERT_PATH):
        try:
            with open(CA_CERT_PATH, "rb") as cert_file:
                cert_data = cert_file.read()
                cert = Cert.from_pem(cert_data)
                if (cert.cn == "PCAPdroid CA") and (not cert.has_expired()):
                    # valid
                    return
                print(cert.cn)
        except Exception as e:
            print(e)

    # needs generation
    print("Generating certificates...")
    CertStore.create_store(Path(MITMPROXY_CONF_DIR), "mitmproxy", 2048, "PCAPdroid", "PCAPdroid CA")

# Entrypoint: returns the mitmproxy CA certificate PEM
def getCAcert() -> str:
    checkCertificate()

    try:
        with open(CA_CERT_PATH, "r") as cert_file:
            return cert_file.read()
    except IOError as e:
        print(e)
        return None

# Entrypoint: reloads the Js Injector userscripts
def reloadJsUserscripts():
    if js_injector:
        js_injector.needs_scripts_reload = True