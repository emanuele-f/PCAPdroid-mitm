# Backport SOCKS5 authentication
# https://github.com/mitmproxy/mitmproxy/pull/4780

import mitmproxy
from mitmproxy import ctx
from .mode import Socks5Proxy as Socks5Proxy_fixhup

orig_configure = mitmproxy.addons.proxyauth.ProxyAuth.configure

def socks5_auth(self, data) -> None:
  if (data.username == self.socks5_username) and (data.password == self.socks5_password):
    data.valid = True
    self.authenticated[data.client_conn] = data.username, data.password

def configure_fixhup(self: mitmproxy.addons.proxyauth.ProxyAuth, updated):
  if ("proxyauth" in updated) and (ctx.options.mode == "socks5") and (":" in ctx.options.proxyauth):
    parts = ctx.options.proxyauth.split(":")
    if len(parts) != 2:
      raise exceptions.OptionsError("Invalid socks5 auth specification.")

    self.socks5_username, self.socks5_password = parts
    return

  # fallback
  orig_configure(self, updated)

# Fixhups
mitmproxy.proxy.layers.modes.Socks5Proxy = Socks5Proxy_fixhup
mitmproxy.addons.proxyauth.ProxyAuth.configure = configure_fixhup
mitmproxy.addons.proxyauth.ProxyAuth.socks5_auth = socks5_auth
