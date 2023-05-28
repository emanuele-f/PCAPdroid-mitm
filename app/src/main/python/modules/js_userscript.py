from modules.url_matcher import UrlMatcher

class JsUserscript:
    def __init__(self):
        self.name = ""
        self.author = ""
        self.version = ""
        self.description = ""
        self.match = []
        self.require = []
        self.content = ""

    """
    Parses a userscript header in the Tampermonkey syntax.
    Stops at the first non-comment non-empty line.
    See See https://www.tampermonkey.net/documentation.php
    """
    @staticmethod
    def parse(stream):
        script = JsUserscript()
        mapped_attr = ["name", "author", "version", "description"]
        content = []

        for line in stream:
            line = line.strip()
            if not line:
                continue

            if not line.startswith("// "):
                content.append(line)
                break

            line = line[3:]
            if line.startswith("@"):
                space = line.find(" ")

                if space > 0:
                    key = line[1:space]
                    value = line[space+1:]

                    if (key == "match"):
                        script.match.append(UrlMatcher(value))
                    elif (key == "require"):
                        script.require.append(value)
                    elif key in mapped_attr:
                        setattr(script, key, value)

        for line in stream:
            content.append(line)

        script.content = "".join(content)
        return script

    def matches(self, http_or_https, domain, path):
        for test in self.match:
            if test.matches(http_or_https, domain, path):
                return True
        return False

if __name__ == "__main__":
    sample_script = """
// ==UserScript==
// @name My script
// @description blabla
// @author Me

// @version 0.1
// @require https://code.jquery.com/jquery-2.1.4.min.js
// @match https://*/*
// @match http://*/foo*
// @match http*://somedomain.net
// @match https://*.tampermonkey.net/foo*bar
// ==/UserScript==

alert(1);
"""

    script = JsUserscript.parse(sample_script.split("\n"))
    print(f"Name: {script.name}")
    print(f"Author: {script.author}")
    print(f"Version: {script.version}")
    print(f"Description: {script.description}")
    print(f"Match: {script.match}")
    print(f"Require: {script.require}")
