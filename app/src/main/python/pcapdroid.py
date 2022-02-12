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

# example usage: mitmdump -q -p 8050 --mode socks5 -s pcapdroid.py
from enum import Enum
import socket
import errno
import mitmproxy
from mitmproxy import http, ctx
from mitmproxy.net.http.http1.assemble import assemble_request, assemble_response

class PayloadType(Enum):
  HTTP_REQUEST = "http_req"
  HTTP_REPLY = "http_rep"
  WEBSOCKET_CLIENT_MSG = "ws_climsg"
  WEBSOCKET_SERVER_MSG = "ws_srvmsg"

class PCAPdroid:
  def __init__(self, fd: int):
    try:
      self.sock = socket.fromfd(fd, socket.AF_INET, socket.SOCK_STREAM)
      #self.sock.connect(("127.0.0.1", 5750))
    except socket.error as e:
      ctx.log.error(e)
      ctx.master.shutdown()

  def send_payload(self, flow: http.HTTPFlow, payload_type: PayloadType, payload):
    client_port = flow.client_conn.peername[1]
    header = "%u:%s:%u\n" % (client_port, payload_type.value, len(payload))
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

  def request(self, flow: http.HTTPFlow):
    if flow.request:
      self.send_payload(flow, PayloadType.HTTP_REQUEST, assemble_request(flow.request))

  def response(self, flow: http.HTTPFlow) -> None:
    if flow.response:
      self.send_payload(flow, PayloadType.HTTP_REPLY, assemble_response(flow.response))

  def log(self, entry: mitmproxy.log.LogEntry):
    print("[%s] %s" % (entry.level, entry.msg))

  def websocket_message(self, flow: http.HTTPFlow):
    msg = flow.websocket.messages[-1]
    if not msg:
      return

    payload_type = PayloadType.WEBSOCKET_CLIENT_MSG if msg.from_client else PayloadType.WEBSOCKET_SERVER_MSG
    self.send_payload(flow, payload_type, msg.content)

#addons = [
#  PCAPdroid()
#]