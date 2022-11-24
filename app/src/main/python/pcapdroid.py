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

import socket
import errno
import time
import mitmproxy
import traceback
from mitmproxy import http, ctx
from mitmproxy.net.http.http1.assemble import assemble_request, assemble_response
from mitmproxy.proxy import server_hooks
from enum import Enum
from java import jclass

Log = jclass("android.util.Log")

# mitmproxy log level -> android level
str2lvl = {
    "debug": Log.DEBUG,
    "alert": Log.DEBUG,
    "info" : Log.INFO,
    "warn" : Log.WARN,
    "error": Log.ERROR,
}

class MsgType(Enum):
    RUNNING = "running"
    TLS_ERROR = "tls_err"
    HTTP_ERROR = "http_err"
    HTTP_REQUEST = "http_req"
    HTTP_REPLY = "http_rep"
    TCP_CLIENT_MSG = "tcp_climsg"
    TCP_SERVER_MSG = "tcp_srvmsg"
    TCP_ERROR = "tcp_err"
    WEBSOCKET_CLIENT_MSG = "ws_climsg"
    WEBSOCKET_SERVER_MSG = "ws_srvmsg"
    MASTER_SECRET = "secret"
    LOG = "log"

class PCAPdroid:
    def __init__(self, sock: socket.socket, dump_client: bool, dump_master_secrets: bool):
        self.sock = sock
        self.dump_client = dump_client

        if dump_master_secrets:
            mitmproxy.net.tls.log_master_secret = self.log_master_secret
        else:
            mitmproxy.net.tls.log_master_secret = None

    def send_message(self, tstamp: float, client_conn: mitmproxy.connection.Client,
            server_conn: mitmproxy.connection.Server, payload_type: MsgType, payload):
        port = 0
        if self.dump_client and client_conn:
            port = client_conn.peername[1]
        elif not self.dump_client and server_conn:
            port = server_conn.sockname[1]

        tstamp_millis = int((tstamp or time.time()) * 1000)

        header = "%u:%u:%s:%u\n" % (tstamp_millis, port, payload_type.value, len(payload))
        #ctx.log.debug(header)

        try:
            self.sock.sendall(header.encode('ascii'))
            self.sock.sendall(payload)
        except socket.error as e:
            if e.errno == errno.EPIPE:
                ctx.log.info("PCAPdroid closed")
                ctx.master.shutdown()
            else:
                ctx.log.error(e)
                ctx.master.shutdown()

    def running(self):
        self.send_message(time.time(), None, None, MsgType.RUNNING, b'')

    def server_error(self, data: server_hooks.ServerConnectionHookData):
        self.send_message(time.time(), data.client, data.server, MsgType.TCP_ERROR, data.server.error.encode("ascii"))

    def request(self, flow: http.HTTPFlow):
        if flow.request:
            self.send_message(flow.request.timestamp_start, flow.client_conn, flow.server_conn, MsgType.HTTP_REQUEST, assemble_request(flow.request))

    def response(self, flow: http.HTTPFlow) -> None:
        if flow.response:
            self.send_message(flow.response.timestamp_start, flow.client_conn, flow.server_conn, MsgType.HTTP_REPLY, assemble_response(flow.response))

    def tcp_message(self, flow: mitmproxy.tcp.TCPFlow):
        msg = flow.messages[-1]
        if not msg:
             return

        payload_type = MsgType.TCP_CLIENT_MSG if msg.from_client else MsgType.TCP_SERVER_MSG
        self.send_message(msg.timestamp, flow.client_conn, flow.server_conn, payload_type, msg.content)

    def websocket_message(self, flow: http.HTTPFlow):
        msg = flow.websocket.messages[-1]
        if not msg:
            return

        payload_type = MsgType.WEBSOCKET_CLIENT_MSG if msg.from_client else MsgType.WEBSOCKET_SERVER_MSG
        self.send_message(msg.timestamp, flow.client_conn, flow.server_conn, payload_type, msg.content)

    def log_master_secret(self, ssl_connection, keymaterial: bytes):
        self.send_message(time.time(), None, None, MsgType.MASTER_SECRET, keymaterial)

    def tls_failed_client(self, data: mitmproxy.tls.TlsData):
        self.send_message(time.time(), data.context.client, data.context.server, MsgType.TLS_ERROR, data.conn.error.encode("ascii"))

    def tls_failed_server(self, data: mitmproxy.tls.TlsData):
        self.send_message(time.time(), data.context.client, data.context.server, MsgType.TLS_ERROR, data.conn.error.encode("ascii"))

    def error(self, flow: http.HTTPFlow):
        self.send_message(time.time(), flow.context.client, data.context.server, MsgType.HTTP_ERROR, flow.error.encode("ascii"))

    def tcp_error(self, flow: mitmproxy.tcp.TCPFlow):
        self.send_message(time.time(), flow.context.client, data.context.server, MsgType.TCP_ERROR, flow.error.encode("ascii"))

    def do_log(self, msg, lvl=Log.INFO):
        Log.println(lvl, "mitmproxy", msg)

        try:
            self.send_message(time.time(), None, None, MsgType.LOG, (str(lvl) + ":" + msg).encode("ascii"))
        except:
            pass

    def add_log(self, entry: mitmproxy.log.LogEntry):
        lvl = str2lvl.get(entry.level, Log.DEBUG)

        if lvl >= Log.ERROR:
            for line in traceback.format_stack():
                self.do_log(line, lvl)

        self.do_log(entry.msg, lvl)