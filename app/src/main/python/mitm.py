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

from mitmproxy import options
from mitmproxy.tools import dump, cmdline
from mitmproxy.tools.main import mitmdump, process_options
from mitmproxy.certs import CertStore, Cert
from pcapdroid import PCAPdroid
from pathlib import Path
import socket
import asyncio
import typing
import sys
import os

MITMPROXY_CONF_DIR = os.environ["HOME"] + "/.mitmproxy"
CA_CERT_PATH = MITMPROXY_CONF_DIR + "/mitmproxy-ca-cert.cer"

# no extra newline
import builtins
builtins.print = lambda x: sys.stdout.write(str(x))

master = None

# Entrypoint: runs mitmproxy
# From mitmproxy.tools.main.run, without the signal handlers
def run(fd: int, port: int):
    global master

    # see also: ssl_insecure
    arguments = f"-q --mode socks5 --listen-host 127.0.0.1 -p {port}".split()

    try:
        with socket.fromfd(fd, socket.AF_INET, socket.SOCK_STREAM) as sock:
            loop = asyncio.new_event_loop()
            asyncio.set_event_loop(loop)

            opts = options.Options()
            master = dump.DumpMaster(opts)

            parser = cmdline.mitmdump(opts)
            args = parser.parse_args(arguments)
            process_options(parser, opts, args)
            checkCertificate()

            master.addons.add(PCAPdroid(sock))

            print("Running mitmdump...")
            master.run()

            # The proxyserver is not stopped by master.shutdown. Must be
            # stopped to properly close the TCP socket.
            proxyserver = master.addons.lookup.get("proxyserver")
            if proxyserver:
                asyncio.run(proxyserver.shutdown_server())

            print("mitmdump stopped")
    except socket.error as e:
        print(e)

# Entrypoint: stops the running mitmproxy
def stop():
    if master:
        print("Stopping mitmdump...")
        master.shutdown()

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