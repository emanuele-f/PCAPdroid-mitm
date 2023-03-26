import asyncio
import logging
from collections.abc import Callable
from mitmproxy import log
from mitmproxy.log import LogEntry

# from mitmproxy eventstore.py
class CallbackLogger(log.MitmLogHandler):
    def __init__(
            self,
            callback: Callable[[LogEntry], None],
    ):
        super().__init__()
        self.callback = callback
        self.event_loop = asyncio.get_running_loop()
        self.formatter = log.MitmFormatter(colorize=False)

    def emit(self, record: logging.LogRecord) -> None:
        entry = LogEntry(
            msg=self.format(record),
            level=log.LOGGING_LEVELS_TO_LOGENTRY.get(record.levelno, "error"),
        )
        self.event_loop.call_soon_threadsafe(self.callback, entry)