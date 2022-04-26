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
from mitmproxy import http, ctx
from mitmproxy.net.http.http1.assemble import assemble_request, assemble_response
from enum import Enum

class PayloadType(Enum):
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

class PCAPdroid:
  def __init__(self, sock: socket.socket, dump_master_secrets: bool):
    self.sock = sock

    if dump_master_secrets:
      mitmproxy.net.tls.log_master_secret = self.log_master_secret
    else:
      mitmproxy.net.tls.log_master_secret = None

  def send_payload(self, tstamp: float, client_conn: mitmproxy.connection.Client, payload_type: PayloadType, payload):
    client_port = client_conn.peername[1] if client_conn else 0
    tstamp_millis = int((tstamp or time.time()) * 1000)

    header = "%u:%u:%s:%u\n" % (tstamp_millis, client_port, payload_type.value, len(payload))
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
    self.send_payload(time.time(), None, PayloadType.RUNNING, b'')

  def request(self, flow: http.HTTPFlow):
    if flow.request:
      self.send_payload(flow.request.timestamp_start, flow.client_conn, PayloadType.HTTP_REQUEST, assemble_request(flow.request))

  def response(self, flow: http.HTTPFlow) -> None:
    if flow.response:
      self.send_payload(flow.response.timestamp_start, flow.client_conn, PayloadType.HTTP_REPLY, assemble_response(flow.response))

  def tcp_message(self, flow: mitmproxy.tcp.TCPFlow):
    msg = flow.messages[-1]
    if not msg:
       return

    payload_type = PayloadType.TCP_CLIENT_MSG if msg.from_client else PayloadType.TCP_SERVER_MSG
    self.send_payload(msg.timestamp, flow.client_conn, payload_type, msg.content)

  def websocket_message(self, flow: http.HTTPFlow):
    msg = flow.websocket.messages[-1]
    if not msg:
      return

    payload_type = PayloadType.WEBSOCKET_CLIENT_MSG if msg.from_client else PayloadType.WEBSOCKET_SERVER_MSG
    self.send_payload(msg.timestamp, flow.client_conn, payload_type, msg.content)

  def log_master_secret(self, ssl_connection, keymaterial: bytes):
    self.send_payload(time.time(), None, PayloadType.MASTER_SECRET, keymaterial)

  def tls_failed_client(self, data: mitmproxy.tls.TlsData):
    self.send_payload(time.time(), data.context.client, PayloadType.TLS_ERROR, data.conn.error.encode("ascii"))

  def tls_failed_server(self, data: mitmproxy.tls.TlsData):
    self.send_payload(time.time(), data.context.client, PayloadType.TLS_ERROR, data.conn.error.encode("ascii"))

  def error(self, flow: http.HTTPFlow):
    self.send_payload(time.time(), flow.context.client, PayloadType.HTTP_ERROR, flow.error.encode("ascii"))

  def tcp_error(self, flow: mitmproxy.tcp.TCPFlow):
    self.send_payload(time.time(), flow.context.client, PayloadType.TCP_ERROR, flow.error.encode("ascii"))

  def add_log(self, entry: mitmproxy.log.LogEntry):
    if(entry.level != "debug") and (entry.level != "info"):
      print("[%s] %s" % (entry.level, entry.msg))