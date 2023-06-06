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

import socket
import errno
import time
import mitmproxy
import traceback
from mitmproxy import http, ctx
from mitmproxy.net.http.http1.assemble import assemble_request, assemble_response
from mitmproxy.proxy import server_hooks
from mitmproxy.log import LogEntry
from enum import Enum
from java import jclass
from modules.callback_logger import CallbackLogger

Log = jclass("android.util.Log")

# mitmproxy log level -> android level
str2lvl = {
    "debug": Log.DEBUG,
    "alert": Log.DEBUG,
    "info" : Log.INFO,
    "warn" : Log.WARN,
    "error": Log.ERROR,
}

SHORT_PAYLOAD_MAX_DIRECTION_SIZE = 512

class AddonOpts:
    def __init__(self, dump_client, dump_keylog, short_payload):
        self.dump_client = dump_client
        self.dump_keylog = dump_keylog
        self.short_payload = short_payload

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
    DATA_TRUNCATED = "trunc"
    MASTER_SECRET = "secret"
    LOG = "log"
    JS_INJECTED = "js_inject"

# pcapdroid per-flow state
class FlowData:
    request_sent: bool = False
    response_sent: bool = False
    truncated: bool = False

# A mitmproxy addon
# See https://docs.mitmproxy.org/stable/api/events.html
class PCAPdroid:
    def __init__(self, sock: socket.socket, opts: AddonOpts):
        self.sock = sock
        self.opts = opts
        self.shutting_down = False

        # intercept log events from mitmproxy
        self.logger = CallbackLogger(self._add_log)
        self.logger.install()

        if opts.dump_keylog:
            mitmproxy.net.tls.log_master_secret = self.log_master_secret
        else:
            mitmproxy.net.tls.log_master_secret = None

    # override
    def done(self):
        print("PCAPdroid done")
        self.logger.uninstall()

    def send_message(self, tstamp: float, client_conn: mitmproxy.connection.Client,
            server_conn: mitmproxy.connection.Server, payload_type: MsgType, payload: bytes):
        port = 0
        if self.opts.dump_client and client_conn:
            port = client_conn.peername[1]
        elif not self.opts.dump_client and server_conn:
            port = server_conn.sockname[1]

        tstamp_millis = int((tstamp or time.time()) * 1000)

        header = "%u:%u:%s:%u\n" % (tstamp_millis, port, payload_type.value, len(payload))

        try:
            self.sock.sendall(header.encode('ascii'))
            self.sock.sendall(payload)
        except socket.error as e:
            if e.errno == errno.EPIPE:
                if not self.shutting_down:
                    self.shutting_down = True
                    print("PCAPdroid closed")
                    ctx.master.shutdown()
            else:
                if not self.shutting_down:
                    self.shutting_down = True
                    print(e)
                    ctx.master.shutdown()

    def getFlowData(self, flow):
        # Extend the flow with additional data
        if not getattr(flow, "pd_data", None):
            flow.pd_data = FlowData()
        return flow.pd_data

    def checkPayload(self, flow, data, req):
        # short payload works as follows:
        # 1. send at most MINIMAL_PAYLOAD_MAX_DIRECTION_SIZE bytes, per direction (send / receive)
        # 2. send DATA_TRUNCATED message if data is truncated
        flow_data = self.getFlowData(flow)

        if not self.opts.short_payload:
            return data
        if flow_data.truncated:
            return

        sent_flag = flow_data.request_sent if req else flow_data.response_sent
        if sent_flag:
            flow_data.truncated = True
            data = None
        elif len(data) >= SHORT_PAYLOAD_MAX_DIRECTION_SIZE:
            flow_data.truncated = True
            data = data[:SHORT_PAYLOAD_MAX_DIRECTION_SIZE]

        if flow_data.truncated:
            self.send_message(time.time(), flow.client_conn, flow.server_conn, MsgType.DATA_TRUNCATED, b"")

        if req:
            flow_data.request_sent = True
        else:
            flow_data.response_sent = True
        return data

    # override
    def running(self):
        self.send_message(time.time(), None, None, MsgType.RUNNING, b'')

    def server_error(self, data: server_hooks.ServerConnectionHookData):
        self.send_message(time.time(), data.client, data.server, MsgType.TCP_ERROR, data.server.error.encode("ascii"))

    # override
    def request(self, flow: http.HTTPFlow):
        if flow.request:
            data = self.checkPayload(flow, assemble_request(flow.request), req=True)
            if data:
                self.send_message(flow.request.timestamp_start, flow.client_conn, flow.server_conn, MsgType.HTTP_REQUEST, data)

    # override
    def response(self, flow: http.HTTPFlow) -> None:
        if flow.response:
            if hasattr(flow, "js_injector_scripts"):
                self.send_message(flow.response.timestamp_start, flow.client_conn, flow.server_conn,
                                  MsgType.JS_INJECTED, flow.js_injector_scripts.encode("ascii"))

            data = self.checkPayload(flow, assemble_response(flow.response), req=False)
            if data:
                self.send_message(flow.response.timestamp_start, flow.client_conn, flow.server_conn, MsgType.HTTP_REPLY, data)

    # override
    def tcp_message(self, flow: mitmproxy.tcp.TCPFlow):
        msg = flow.messages[-1]
        if not msg:
             return

        data = msg.content
        payload_type = None

        if msg.from_client:
            payload_type = MsgType.TCP_CLIENT_MSG
            data = self.checkPayload(flow, data, req=True)
        else:
            payload_type = MsgType.TCP_SERVER_MSG
            data = self.checkPayload(flow, data, req=False)

        if data:
            self.send_message(msg.timestamp, flow.client_conn, flow.server_conn, payload_type, data)

    # override
    def websocket_message(self, flow: http.HTTPFlow):
        msg = flow.websocket.messages[-1]
        if not msg:
            return

        data = msg.content
        payload_type = None

        if msg.from_client:
            payload_type = MsgType.WEBSOCKET_CLIENT_MSG
            data = self.checkPayload(flow, data, req=True)
        else:
            payload_type = MsgType.WEBSOCKET_SERVER_MSG
            data = self.checkPayload(flow, data, req=False)

        if data:
            self.send_message(msg.timestamp, flow.client_conn, flow.server_conn, payload_type, data)

    def log_master_secret(self, ssl_connection, keymaterial: bytes):
        self.send_message(time.time(), None, None, MsgType.MASTER_SECRET, keymaterial)

    # override
    def tls_failed_client(self, data: mitmproxy.tls.TlsData):
        self.send_message(time.time(), data.context.client, data.context.server, MsgType.TLS_ERROR, data.conn.error.encode("ascii"))

    # override
    def tls_failed_server(self, data: mitmproxy.tls.TlsData):
        self.send_message(time.time(), data.context.client, data.context.server, MsgType.TLS_ERROR, data.conn.error.encode("ascii"))

    # override
    def error(self, flow: http.HTTPFlow):
        self.send_message(time.time(), flow.client_conn, flow.server_conn, MsgType.HTTP_ERROR, flow.error.msg.encode("ascii"))

    # override
    def tcp_error(self, flow: mitmproxy.tcp.TCPFlow):
        self.send_message(time.time(), flow.context.client, flow.context.server, MsgType.TCP_ERROR, flow.error.msg.encode("ascii"))

    def log(self, msg, lvl=Log.INFO):
        Log.println(lvl, "mitmproxy", msg)

        try:
            self.send_message(time.time(), None, None, MsgType.LOG, (str(lvl) + ":" + msg).encode("ascii"))
        except:
            pass

    def log_warn(self, msg):
        self.log(msg, lvl=Log.WARN)

    def _add_log(self, entry: LogEntry):
        lvl = str2lvl.get(entry.level, Log.DEBUG)

        if lvl >= Log.ERROR:
            for line in traceback.format_stack():
                self.log(line, lvl)
        elif lvl == Log.INFO:
            # mitmproxy is very verbose in info messages, treat them as debug
            lvl = Log.DEBUG

        self.log(entry.msg, lvl)
