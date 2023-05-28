import re

class UrlMatcher:
    def __init__(self, pattern):
        self.pattern = pattern
        self.http_only = False
        self.https_only = False
        self.domain = None
        self.path = None
        self.parse()

    def __repr__(self):
        return self.pattern

    def parse(self):
        # <protocol>://<domain><path>
        # E.g. *://*/*

        # protocol
        expr = self.pattern
        if expr.startswith("http://"):
            self.http_only = True
        elif expr.startswith("https://"):
            self.https_only = True
        idx = expr.find("://")
        if idx > 0:
            expr = expr[idx+3:]

        # domain and path
        idx = expr.find("/")
        if idx > 0:
            self.domain = self.to_regex(expr[:idx])
            self.path = self.to_regex(expr[idx+1:])
        else:
            self.domain = self.to_regex(expr)

    def to_regex(self, expr):
        # we only support "*" for now
        r = re.escape(expr)
        r = r.replace("\\*", ".*")
        return re.compile(r)

    def is_valid(self):
        return self.domain

    def matches(self, http_or_https, domain, path=""):
        if not self.is_valid():
            return False

        if path and path[0] == "/":
            path = path[1:]

        # protocol
        if self.http_only and (http_or_https == "https"):
            return False
        if self.https_only and (http_or_https == "http"):
            return False

        # domain
        if not self.domain.match(domain):
            return False

        # path
        if (not self.path and path) or (self.path and not self.path.match(path)):
            return False

        return True

if __name__ == "__main__":
    # test protocol
    assert(UrlMatcher("http://example.com").matches("http", "example.com"))
    assert(not UrlMatcher("http://example.com").matches("https", "example.com"))
    assert(UrlMatcher("http*://example.com").matches("http", "example.com"))
    assert(UrlMatcher("http*://example.com").matches("https", "example.com"))

    # test domain
    assert(UrlMatcher("http://*.example.com/*").matches("http", "some.example.com"))
    assert(UrlMatcher("http://example.*/*").matches("http", "example.it"))
    assert(UrlMatcher("http://*example.*/*").matches("http", "myexample.it"))

    # test path
    assert(UrlMatcher("http://example.com/*").matches("http", "example.com"))
    assert(UrlMatcher("http://example.com/*").matches("http", "example.com", "/"))
    assert(UrlMatcher("http://example.com/*").matches("http", "example.com", "/path"))
    assert(UrlMatcher("http://example.com").matches("http", "example.com", "/"))
    assert(not UrlMatcher("http://example.com").matches("http", "example.com", "/path"))
    assert(UrlMatcher("http://example.com/*.gif").matches("http", "example.com", "/some/path/a.gif"))

    print("All tests passed")
