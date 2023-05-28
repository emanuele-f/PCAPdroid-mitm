from com.pcapdroid.mitm import IJsUserscript
from java import dynamic_proxy
from js_injector import JsInjector

# exposes JsUserscript to Java
class ScriptProxy(dynamic_proxy(IJsUserscript)):
    def __init__(self, script):
        super().__init__()
        self.script = script

    def getName(self):
        return self.script.name

    def getAuthor(self):
        return self.script.author

    def getVersion(self):
        return self.script.version

    def getDescription(self):
        return self.script.description

    def getFname(self):
        return self.script.fname

# Entrypoint: returns the available scripts
def getJsUserscripts() -> list[ScriptProxy]:
    return [ScriptProxy(s) for s in JsInjector.get_scripts()]

# Entrypoint: returns the path of a script
def getScriptPath(fname: str) -> str:
    return JsInjector.getScriptPath(fname)