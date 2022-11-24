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
#  Copyright 2022 - Emanuele Faranda
#

import os

MITMPROXY_CONF_DIR = os.environ["HOME"] + "/.mitmproxy"
CA_CERT_PATH = MITMPROXY_CONF_DIR + "/mitmproxy-ca-cert.cer"

from mitmproxy import options
from mitmproxy.tools import dump, cmdline
from mitmproxy.tools.main import mitmdump, process_options
from mitmproxy.certs import CertStore, Cert
from mitmproxy.proxy import server_hooks
from mitmproxy.proxy.events import OpenConnectionCompleted
from pcapdroid import PCAPdroid
from pathlib import Path
import mitmproxy
import socket
import asyncio
import typing
import sys

master = None
pcapdroid = None
running = False

def my_print(x, *args, **kargs):
    msg = str(x)

    # no extra newline in logcat
    sys.stdout.write(msg)

    if pcapdroid:
        # send log to PCAPdroid
        pcapdroid.do_log(msg)

import builtins
builtins.print = my_print

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

# Entrypoint: runs mitmproxy
# From mitmproxy.tools.main.run, without the signal handlers
def run(fd: int, dump_client: bool, dump_keylog: bool, mitm_args: str):
    global master
    global running
    global pcapdroid
    running = True

    try:
        with socket.fromfd(fd, socket.AF_INET, socket.SOCK_STREAM) as sock:
            async def main():
                global master
                global pcapdroid
                opts = options.Options()
                master = dump.DumpMaster(opts)

                parser = cmdline.mitmdump(opts)
                args = parser.parse_args(mitm_args.split())
                process_options(parser, opts, args)
                checkCertificate()

                pcapdroid = PCAPdroid(sock, dump_client, dump_keylog)
                master.addons.add(pcapdroid)

                ConnectionHandler.server_event = lambda handler, ev: server_event_proxy(handler, ev)

                print("Running mitmdump...")
                await master.run()

                # The proxyserver is not stopped by master.shutdown. Must be
                # stopped to properly close the TCP socket.
                proxyserver = master.addons.lookup.get("proxyserver")
                if proxyserver:
                    print("Stopping proxyserver...")
                    await proxyserver.shutdown_server()

            asyncio.run(main())
    except socket.error as e:
        print(e)

    print("mitmdump stopped")
    master = None
    running = False
    pcapdroid = None

# Entrypoint: stops the running mitmproxy
def stop():
    global running

    if not running:
        return

    print("Stopping mitmdump...")
    running = False

    if master:
        master.shutdown()

# Entrypoint: logs a message to
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