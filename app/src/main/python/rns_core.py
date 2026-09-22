import sys
sys.setrecursionlimit(5000)

try:
    from com.example import AndroidLogger
except Exception:
    AndroidLogger = None

import os
import signal
import threading
import traceback
import time
import json
import socket

# Globally patch socket and SafeSocket so restricted Linux/Unix socket flags and families
# (e.g. AF_NETLINK, AF_UNIX, TCP_NODELAY, SO_KEEPALIVE, multicast setsockopts)
# never make restricted kernel syscalls that trigger SELinux audit rate-limit denials on Android.
_original_setsockopt = socket.socket.setsockopt

_blocked_optnames = {
    getattr(socket, "TCP_NODELAY", 1),
    getattr(socket, "TCP_USER_TIMEOUT", 18),
    getattr(socket, "TCP_KEEPIDLE", 4),
    getattr(socket, "TCP_KEEPINTVL", 5),
    getattr(socket, "TCP_KEEPCNT", 6),
    getattr(socket, "TCP_FASTOPEN", 23),
    getattr(socket, "TCP_KEEPALIVE", 0x10),
    getattr(socket, "SO_KEEPALIVE", 9),
    getattr(socket, "SO_RCVTIMEO", 20),
    getattr(socket, "SO_SNDTIMEO", 21),
    getattr(socket, "SO_BINDTODEVICE", 25),
    getattr(socket, "SO_MARK", 36),
    getattr(socket, "SO_PRIORITY", 12),
    getattr(socket, "SO_RCVBUFFORCE", 33),
    getattr(socket, "SO_SNDBUFFORCE", 32),
}

def _safe_setsockopt(self, level, optname, value, *args, **kwargs):
    if optname in _blocked_optnames or level == getattr(socket, "IPPROTO_TCP", 6):
        return None
    if level == getattr(socket, "IPPROTO_IPV6", 41) and optname in (
        getattr(socket, "IPV6_MULTICAST_IF", 17),
        getattr(socket, "IPV6_JOIN_GROUP", 20),
        getattr(socket, "IPV6_MULTICAST_HOPS", 18),
        getattr(socket, "IPV6_MULTICAST_LOOP", 19),
    ):
        return None
    try:
        return _original_setsockopt(self, level, optname, value, *args, **kwargs)
    except (OSError, PermissionError, AttributeError):
        return None
    except Exception:
        return None

socket.socket.setsockopt = _safe_setsockopt

try:
    import _socket
except Exception:
    _socket = None

if _socket is not None and hasattr(_socket.socket, "setsockopt"):
    try:
        _socket.socket.setsockopt = _safe_setsockopt
    except Exception:
        pass

_orig_socket_class = socket.socket
class SafeSocket(_orig_socket_class):
    def __new__(cls, family=socket.AF_INET, type=socket.SOCK_STREAM, proto=0, fileno=None):
        if fileno is None:
            # Block any socket family other than AF_INET and AF_INET6 (e.g. AF_NETLINK, AF_UNIX, AF_PACKET)
            if family not in (getattr(socket, "AF_INET", 2), getattr(socket, "AF_INET6", 10), -1):
                raise PermissionError(f"Socket family {family} disabled on Android")
            # Block raw and packet sockets before kernel syscall
            sock_type = type if isinstance(type, int) else getattr(type, "value", 1)
            base_type = sock_type & 0xF
            if base_type not in (getattr(socket, "SOCK_STREAM", 1), getattr(socket, "SOCK_DGRAM", 2), 0):
                raise PermissionError(f"Socket type {type} disabled on Android")
        return super().__new__(cls, family, type, proto, fileno)

    def __init__(self, family=-1, type=-1, proto=-1, fileno=None):
        if fileno is None:
            if family not in (getattr(socket, "AF_INET", 2), getattr(socket, "AF_INET6", 10), -1):
                raise PermissionError(f"Socket family {family} disabled on Android")
            sock_type = type if isinstance(type, int) else getattr(type, "value", 1)
            base_type = sock_type & 0xF
            if base_type not in (getattr(socket, "SOCK_STREAM", 1), getattr(socket, "SOCK_DGRAM", 2), 0):
                raise PermissionError(f"Socket type {type} disabled on Android")
        super().__init__(family, type, proto, fileno)

    def setsockopt(self, level, optname, value, *args, **kwargs):
        if optname in _blocked_optnames or level == getattr(socket, "IPPROTO_TCP", 6):
            return None
        if level == getattr(socket, "IPPROTO_IPV6", 41) and optname in (
            getattr(socket, "IPV6_MULTICAST_IF", 17),
            getattr(socket, "IPV6_JOIN_GROUP", 20),
            getattr(socket, "IPV6_MULTICAST_HOPS", 18),
            getattr(socket, "IPV6_MULTICAST_LOOP", 19),
        ):
            return None
        try:
            return super().setsockopt(level, optname, value, *args, **kwargs)
        except (OSError, PermissionError, AttributeError):
            return None
        except Exception:
            return None

    def bind(self, address):
        if getattr(self, "family", None) in (getattr(socket, "AF_UNIX", 1), getattr(socket, "AF_NETLINK", 16)):
            raise PermissionError("Socket family disabled on Android")
        if isinstance(address, (str, bytes)):
            addr_str = address.decode("latin1", "ignore") if isinstance(address, bytes) else str(address)
            if addr_str.startswith("\x00") or addr_str.startswith("\\0"):
                raise PermissionError("Abstract UNIX domain sockets disabled on Android")
        return super().bind(address)

    def connect(self, address):
        if getattr(self, "family", None) in (getattr(socket, "AF_UNIX", 1), getattr(socket, "AF_NETLINK", 16)):
            raise PermissionError("Socket family disabled on Android")
        if isinstance(address, (str, bytes)):
            addr_str = address.decode("latin1", "ignore") if isinstance(address, bytes) else str(address)
            if addr_str.startswith("\x00") or addr_str.startswith("\\0"):
                raise PermissionError("Abstract UNIX domain sockets disabled on Android")
        return super().connect(address)

socket.socket = SafeSocket
socket.SocketType = SafeSocket

def _safe_socketpair(family=None, type=socket.SOCK_STREAM, proto=0):
    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(('127.0.0.1', 0))
    listener.listen(1)
    port = listener.getsockname()[1]
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect(('127.0.0.1', port))
    server, _ = listener.accept()
    listener.close()
    return server, client

socket.socketpair = _safe_socketpair

# Neutralize socket interface query functions that execute restricted netlink or SIOCGIF* ioctl syscalls
_dummy_if_list = [(1, "lo")]
socket.if_nameindex = lambda: _dummy_if_list
socket.if_nametoindex = lambda name: 1
socket.if_indextoname = lambda idx: "lo"
if _socket is not None:
    _socket.if_nameindex = lambda: _dummy_if_list
    _socket.if_nametoindex = lambda name: 1
    _socket.if_indextoname = lambda idx: "lo"

socket.gethostname = lambda: "localhost"
socket.getfqdn = lambda *a, **k: "localhost"

# Prevent load average probing into /proc/loadavg
if hasattr(os, "getloadavg"):
    os.getloadavg = lambda: (0.0, 0.0, 0.0)

# Mock uuid.getnode to avoid kernel netlink and mac address queries
try:
    import uuid
    uuid.getnode = lambda: 0x020000000001
except Exception:
    pass

# Mock pwd and grp to avoid /etc/passwd and /etc/group access
try:
    import pwd
    import collections
    _struct_passwd = collections.namedtuple("struct_passwd", ["pw_name", "pw_passwd", "pw_uid", "pw_gid", "pw_gecos", "pw_dir", "pw_shell"])
    _dummy_pw = _struct_passwd("u0_a123", "x", 10123, 10123, "Android App", "/data/data/com.example/files", "/bin/sh")
    pwd.getpwuid = lambda uid: _dummy_pw
    pwd.getpwnam = lambda name: _dummy_pw
    pwd.getpwall = lambda: [_dummy_pw]
except Exception:
    pass

try:
    import grp
    import collections
    _struct_group = collections.namedtuple("struct_group", ["gr_name", "gr_passwd", "gr_gid", "gr_mem"])
    _dummy_gr = _struct_group("u0_a123", "x", 10123, [])
    grp.getgrgid = lambda gid: _dummy_gr
    grp.getgrnam = lambda name: _dummy_gr
    grp.getgrall = lambda: [_dummy_gr]
except Exception:
    pass

# Inject mock netinfo module in sys.modules before any imports
import types
_mock_netinfo = types.ModuleType("RNS.Interfaces.util.netinfo")
_mock_netinfo.AF_INET = getattr(socket, "AF_INET", 2)
_mock_netinfo.AF_INET6 = getattr(socket, "AF_INET6", 10)
_mock_netinfo.interfaces = lambda: ["lo"]
_mock_netinfo.ifaddresses = lambda ifname: {
    getattr(socket, "AF_INET", 2): [{"addr": "127.0.0.1", "broadcast": "127.0.0.1", "netmask": "255.0.0.0"}],
    getattr(socket, "AF_INET6", 10): [],
}
_mock_netinfo.interface_names_to_indexes = lambda: {"lo": 1}
_mock_netinfo.interface_name_to_nice_name = lambda ifname: "Loopback"
_mock_netinfo.get_adapters = lambda include_unconfigured=False: []

class _MockIP:
    def __init__(self, ip, prefixlen, name):
        self.ip = ip
        self.network_prefix = prefixlen
        self.nice_name = name
        self.is_IPv4 = True
        self.is_IPv6 = False

class _MockAdapter:
    def __init__(self, name, nice_name, ips, index=1):
        self.name = name
        self.nice_name = nice_name
        self.ips = ips
        self.index = index

_mock_netinfo.IP = _MockIP
_mock_netinfo.Adapter = _MockAdapter

sys.modules["RNS.Interfaces.util.netinfo"] = _mock_netinfo
sys.modules["RNS.Interfaces.netinfo"] = _mock_netinfo
sys.modules["netinfo"] = _mock_netinfo

# Hook ctypes CDLL and ctypes.util to avoid getifaddrs kernel netlink probes and ldconfig/gcc subprocesses
try:
    import ctypes
    def _dummy_func(*args, **kwargs):
        return -1
    def _dummy_void(*args, **kwargs):
        return None
    _BLOCKED_CDLL_FUNCS = {
        "getifaddrs": _dummy_func,
        "freeifaddrs": _dummy_void,
        "ioctl": _dummy_func,
        "siocgifconf": _dummy_func,
    }
    _orig_cdll_getattr = ctypes.CDLL.__getattr__
    def _safe_cdll_getattr(self, name):
        lower_name = name.lower()
        if lower_name in _BLOCKED_CDLL_FUNCS:
            return _BLOCKED_CDLL_FUNCS[lower_name]
        return _orig_cdll_getattr(self, name)
    ctypes.CDLL.__getattr__ = _safe_cdll_getattr
    _orig_cdll_getitem = ctypes.CDLL.__getitem__
    def _safe_cdll_getitem(self, name):
        lower_name = name.lower()
        if lower_name in _BLOCKED_CDLL_FUNCS:
            return _BLOCKED_CDLL_FUNCS[lower_name]
        return _orig_cdll_getitem(self, name)
    ctypes.CDLL.__getitem__ = _safe_cdll_getitem

    import ctypes.util
    def _safe_find_library(name):
        if name in ("c", "m", "dl", "z"):
            return f"lib{name}.so"
        return None
    ctypes.util.find_library = _safe_find_library
except Exception:
    pass

# Ensure HOME environment variable never resolves to /root
if not os.environ.get("HOME") or os.environ.get("HOME") == "/root":
    os.environ["HOME"] = "/data/data/com.example/files"

# Prevent SELinux audit rate-limit denials from system filesystem probing (/etc/reticulum, /proc, /sys, etc.)
_ALLOWED_EXACT_PATHS = {
    "/dev/urandom",
    "/dev/random",
    "/dev/null",
    "/dev/zero",
}

_RESTRICTED_PREFIXES = (
    "/proc",
    "/sys",
    "/etc",
    "/root",
    "/var",
    "/dev",
    "/system",
    "/vendor",
    "/product",
    "/apex",
    "/odm",
    "/sbin",
    "/bin",
    "/usr",
    "/data/local",
    "/data/misc",
    "/data/system",
    "/data/property",
    "/data/tombstones",
    "/data/anr",
    "/data/core",
)

def _is_restricted_path(path):
    if path is None:
        return False
    if isinstance(path, int):
        return False
    try:
        if isinstance(path, (bytes, bytearray)):
            p = os.fsdecode(path)
        elif hasattr(path, "__fspath__"):
            fsp = path.__fspath__()
            p = os.fsdecode(fsp) if isinstance(fsp, (bytes, bytearray)) else str(fsp)
        else:
            p = str(path)
        p = p.strip()
        if not p:
            return False

        if p in _ALLOWED_EXACT_PATHS:
            return False
        if p.startswith(_RESTRICTED_PREFIXES):
            return True

        norm = os.path.normpath(p)
        if norm in _ALLOWED_EXACT_PATHS:
            return False
        if norm.startswith(_RESTRICTED_PREFIXES):
            return True

        abs_p = os.path.abspath(norm)
        if abs_p in _ALLOWED_EXACT_PATHS:
            return False
        if abs_p.startswith(_RESTRICTED_PREFIXES):
            return True
    except Exception:
        pass
    return False

import builtins
import io
try:
    import _io
except Exception:
    _io = None

_orig_open = builtins.open
def _safe_open(file, *args, **kwargs):
    if _is_restricted_path(file):
        raise FileNotFoundError(2, "No such file or directory", str(file))
    return _orig_open(file, *args, **kwargs)
builtins.open = _safe_open
io.open = _safe_open
if _io is not None:
    _io.open = _safe_open

_orig_os_open = os.open
def _safe_os_open(path, flags, *args, **kwargs):
    if _is_restricted_path(path):
        raise FileNotFoundError(2, "No such file or directory", str(path))
    return _orig_os_open(path, flags, *args, **kwargs)
os.open = _safe_os_open

_orig_stat = os.stat
def _safe_stat(path, *args, **kwargs):
    if _is_restricted_path(path):
        raise FileNotFoundError(2, "No such file or directory", str(path))
    return _orig_stat(path, *args, **kwargs)

_orig_lstat = os.lstat
def _safe_lstat(path, *args, **kwargs):
    if _is_restricted_path(path):
        raise FileNotFoundError(2, "No such file or directory", str(path))
    return _orig_lstat(path, *args, **kwargs)

_orig_access = os.access
def _safe_access(path, mode=os.F_OK, *args, **kwargs):
    if _is_restricted_path(path):
        return False
    if mode & getattr(os, "X_OK", 1):
        return False
    try:
        return _orig_access(path, mode, *args, **kwargs)
    except Exception:
        return False

_orig_scandir = os.scandir
def _safe_scandir(path=".", *args, **kwargs):
    if _is_restricted_path(path):
        raise FileNotFoundError(2, "No such file or directory", str(path))
    return _orig_scandir(path, *args, **kwargs)

_orig_listdir = os.listdir
def _safe_listdir(path=".", *args, **kwargs):
    if _is_restricted_path(path):
        raise FileNotFoundError(2, "No such file or directory", str(path))
    return _orig_listdir(path, *args, **kwargs)

_orig_isdir = os.path.isdir
def _safe_isdir(path):
    if _is_restricted_path(path):
        return False
    try:
        return _orig_isdir(path)
    except Exception:
        return False

_orig_isfile = os.path.isfile
def _safe_isfile(path):
    if _is_restricted_path(path):
        return False
    try:
        return _orig_isfile(path)
    except Exception:
        return False

_orig_exists = os.path.exists
def _safe_exists(path):
    if _is_restricted_path(path):
        return False
    try:
        return _orig_exists(path)
    except Exception:
        return False

_orig_lexists = getattr(os.path, "lexists", None)
def _safe_lexists(path):
    if _is_restricted_path(path):
        return False
    try:
        return _orig_lexists(path) if _orig_lexists else False
    except Exception:
        return False

_orig_readlink = getattr(os, "readlink", None)
def _safe_readlink(path, *args, **kwargs):
    if _is_restricted_path(path):
        raise FileNotFoundError(2, "No such file or directory", str(path))
    if _orig_readlink is not None:
        return _orig_readlink(path, *args, **kwargs)
    raise OSError("readlink not supported")

_orig_statvfs = getattr(os, "statvfs", None)
def _safe_statvfs(path, *args, **kwargs):
    if _is_restricted_path(path):
        raise FileNotFoundError(2, "No such file or directory", str(path))
    if _orig_statvfs is not None:
        return _orig_statvfs(path, *args, **kwargs)
    raise OSError("statvfs not supported")

os.stat = _safe_stat
os.lstat = _safe_lstat
os.access = _safe_access
os.scandir = _safe_scandir
os.listdir = _safe_listdir
os.path.isdir = _safe_isdir
os.path.isfile = _safe_isfile
os.path.exists = _safe_exists
if _orig_lexists:
    os.path.lexists = _safe_lexists
if _orig_readlink:
    os.readlink = _safe_readlink
if _orig_statvfs:
    os.statvfs = _safe_statvfs

try:
    import posix
    posix.open = _safe_os_open
    posix.stat = _safe_stat
    posix.lstat = _safe_lstat
    posix.access = _safe_access
    posix.scandir = _safe_scandir
    posix.listdir = _safe_listdir
    if hasattr(posix, "readlink"):
        posix.readlink = _safe_readlink
    if hasattr(posix, "statvfs"):
        posix.statvfs = _safe_statvfs
except Exception:
    pass

# Neutralize subprocess and process execution (fork, popen, spawn, exec, system)
import io
try:
    import subprocess
    class CompletedProcessMock:
        def __init__(self, args=None, returncode=1, stdout=b"", stderr=b""):
            self.args = args or []
            self.returncode = returncode
            self.stdout = stdout
            self.stderr = stderr
        def check_returncode(self):
            raise PermissionError("Subprocess execution is prohibited on Android")

    subprocess.run = lambda *a, **kw: CompletedProcessMock(args=a, returncode=1, stdout=b"", stderr=b"Disabled on Android")
    def _disabled_popen(*args, **kwargs):
        raise PermissionError("Subprocess execution is prohibited on Android")
    subprocess.Popen = _disabled_popen
    subprocess.call = lambda *a, **kw: 1
    subprocess.check_call = lambda *a, **kw: 1
    subprocess.check_output = lambda *a, **kw: b""
except Exception:
    pass

if hasattr(os, "fork"):
    os.fork = lambda: (_ for _ in ()).throw(PermissionError("Fork is prohibited on Android"))
os.system = lambda *a, **kw: -1
os.popen = lambda *a, **kw: io.StringIO()
for _fn in ("execv", "execve", "execl", "execle", "execlp", "execvpe", "execvp", "spawnl", "spawnle", "spawnlp", "spawnlpe", "spawnv", "spawnve", "spawnvp", "spawnvpe"):
    if hasattr(os, _fn):
        setattr(os, _fn, lambda *a, **kw: None)

# Patch platformutils before RNS or Reticulum imports or initializes
try:
    import RNS.vendor.platformutils
    RNS.vendor.platformutils.use_af_unix = lambda: False
    RNS.vendor.platformutils.use_epoll = lambda: False
except Exception:
    pass

# Event queue for thread-safe polling from Kotlin UI
_event_queue = []
_event_lock = threading.Lock()

try:
    from com.example import DiagnosticLogger
    _diag_logger = DiagnosticLogger
except Exception:
    _diag_logger = None

def log_status(msg):
    str_msg = str(msg).strip()
    if not str_msg:
        return
    if AndroidLogger:
        try:
            AndroidLogger.logPythonStdout(str_msg)
        except Exception:
            pass
    elif _diag_logger:
        try:
            _diag_logger.logPythonRns(str_msg)
        except Exception:
            try:
                _diag_logger.INSTANCE.logPythonRns(str_msg)
            except Exception:
                pass
    with _event_lock:
        if len(_event_queue) < 500:
            _event_queue.append(str_msg)
    if _status_callback:
        try:
            _status_callback(str_msg)
        except Exception:
            pass

class SafeStream:
    def __init__(self, callback):
        self.callback = callback
        self._writing = False

    def write(self, msg):
        if self._writing:
            return
        s = str(msg).strip()
        if not s:
            return
        try:
            self._writing = True
            self.callback(s)
        except Exception:
            pass
        finally:
            self._writing = False

    def flush(self):
        pass

sys.stdout = SafeStream(lambda s: AndroidLogger.logPythonStdout(s) if AndroidLogger else log_status(s))
sys.stderr = SafeStream(lambda s: AndroidLogger.logPythonStderr(s) if AndroidLogger else log_status(f"[ERR] {s}"))

# Prevent embedded Python or RNS from killing the host Android process
def _safe_exit(code=0):
    log_status(f"Notice: exit/panic requested ({code}), prevented to keep app alive.")

os._exit = _safe_exit
sys.exit = _safe_exit

# Prevent ValueError when signal.signal is invoked from background threads (e.g. coroutine Dispatchers.IO)
_original_signal = signal.signal
def _safe_signal(sig, handler):
    try:
        return _original_signal(sig, handler)
    except Exception:
        return None

signal.signal = _safe_signal

import RNS
import LXMF
import RNS.Interfaces.TCPInterface
import RNS.Interfaces.KISSInterface

# Ensure platformutils and netinfo functions remain disabled after RNS import
try:
    RNS.vendor.platformutils.use_af_unix = lambda: False
    RNS.vendor.platformutils.use_epoll = lambda: False
except Exception:
    pass

# Disable external script discovery announces in Discovery
try:
    import RNS.Discovery
    RNS.Discovery.InterfaceAnnouncer.get_interface_announce_data = lambda self, interface: None
except Exception:
    pass

# Disable AutoInterface
try:
    import RNS.Interfaces.AutoInterface
    class DisabledAutoInterface(RNS.Interfaces.Interface.Interface):
        def __init__(self, owner, configuration):
            super().__init__()
            self.HW_MTU = 1000
            self.online = False
            self.IN = False
            self.OUT = False
        def process_outgoing(self, data):
            pass
    RNS.Interfaces.AutoInterface.AutoInterface = DisabledAutoInterface
except Exception:
    pass

try:
    import RNS.Interfaces.Android.KISSInterface
    _has_android_kiss = True
except Exception:
    _has_android_kiss = False

# Reticulum on Android defaults to Kivy/usbserial4a for KISSInterface.
# We patch KISSInterface so that when target_host/target_port are provided,
# it connects via TCPClientInterface with kiss_framing enabled to talk to
# the native Android Kotlin UsbRNodeBridge at 127.0.0.1:4243.
class TCPKISSInterface(RNS.Interfaces.TCPInterface.TCPClientInterface):
    def __init__(self, owner, configuration):
        c = RNS.Interfaces.Interface.Interface.get_config_obj(configuration)
        c["kiss_framing"] = True
        super().__init__(owner, c)
        self.HW_MTU = 508

RNS.Interfaces.KISSInterface.KISSInterface = TCPKISSInterface
if _has_android_kiss:
    RNS.Interfaces.Android.KISSInterface.KISSInterface = TCPKISSInterface

_orig_tcp_init = RNS.Interfaces.TCPInterface.TCPClientInterface.__init__
def _patched_tcp_init(self, owner, configuration):
    c = RNS.Interfaces.Interface.Interface.get_config_obj(configuration)
    if str(c.get("target_port")) == "4243" or c.get("target_host") == "127.0.0.1":
        c["kiss_framing"] = "True"
    _orig_tcp_init(self, owner, c)
    if str(c.get("target_port")) == "4243" or c.get("target_host") == "127.0.0.1":
        self.kiss_framing = True
        self.HW_MTU = 508

RNS.Interfaces.TCPInterface.TCPClientInterface.__init__ = _patched_tcp_init

# Patch RNS panic/exit functions
RNS.panic = _safe_exit
RNS.exit = _safe_exit

# Set loglevel to LOG_INFO to prevent Android system log and audit rate-limiting
RNS.loglevel = RNS.LOG_INFO
_last_logged_msgs = {}

def log_rns_internals(msg, *args, **kwargs):
    try:
        msg_str = str(msg)
        lower_msg = msg_str.lower()
        # Filter high frequency tick, keepalive, and ping noises
        if any(w in lower_msg for w in ("tick", "keepalive", "ping", "heartbeat")):
            return
        # Throttle duplicate consecutive log messages
        now = time.time()
        last_t = _last_logged_msgs.get(msg_str, 0)
        if now - last_t < 1.0:
            return
        _last_logged_msgs[msg_str] = now
        # Keep map size bounded
        if len(_last_logged_msgs) > 100:
            _last_logged_msgs.clear()
        log_status(f"[RNS CORE] {msg_str}")
    except Exception:
        pass

RNS.log = log_rns_internals

# Prevent SELinux audit denials / crashes from unsupported TCP socket options on Android
def _safe_set_timeouts_linux(self):
    pass

def _safe_set_timeouts_osx(self):
    pass

def _safe_set_timeouts_windows(self):
    pass

for _mod_name in ("TCPInterface", "RNodeInterface", "BackboneInterface", "LocalInterface", "I2PInterface"):
    _iface_mod = getattr(RNS.Interfaces, _mod_name, None)
    if _iface_mod:
        for _cls_name in ("TCPClientInterface", "TCPServerInterface", "RNodeInterface", "BackboneClientInterface", "BackboneInterface", "LocalClientInterface", "LocalServerInterface", "I2PInterface"):
            _cls_obj = getattr(_iface_mod, _cls_name, None)
            if _cls_obj:
                setattr(_cls_obj, "set_timeouts_linux", _safe_set_timeouts_linux)
                setattr(_cls_obj, "set_timeouts_osx", _safe_set_timeouts_osx)
                setattr(_cls_obj, "set_timeouts_windows", _safe_set_timeouts_windows)

if _has_android_kiss:
    try:
        import RNS.Interfaces.Android.RNodeInterface
        RNS.Interfaces.Android.RNodeInterface.RNodeInterface.set_timeouts_linux = _safe_set_timeouts_linux
        RNS.Interfaces.Android.RNodeInterface.RNodeInterface.set_timeouts_osx = _safe_set_timeouts_osx
        RNS.Interfaces.Android.RNodeInterface.RNodeInterface.set_timeouts_windows = _safe_set_timeouts_windows
    except Exception:
        pass

_status_callback = None
_announce_callback = None
_message_callback = None
_announce_handler = None

_discovered_peers = {}
_discovered_lock = threading.Lock()

_messages_history = []
_messages_lock = threading.Lock()

rns = None
router = None
delivery_dest = None
identity = None
identity_hash = ""

class UniversalAnnounceHandler:
    # None = catch ALL announces across the mesh (LXMF, RRC, nodes, etc.)
    aspect_filter = None

    def received_announce(self, destination_hash, announced_identity, app_data):
        try:
            dest_hex = RNS.hexrep(destination_hash, delimit=False)
            log_status(f"!!! HANDLER RECEIVED ANNOUNCE: {dest_hex[:8]} !!!")
            
            # Unpack display name or app_data
            name = ""
            if app_data:
                try:
                    name = app_data.decode("utf-8", "ignore").strip()
                except Exception:
                    name = str(app_data)
            
            hops = RNS.Transport.hops_to(destination_hash)
            if hops is None:
                hops = 1

            with _discovered_lock:
                _discovered_peers[dest_hex] = {
                    "hash": dest_hex,
                    "name": name if name else dest_hex[:8],
                    "hops": int(hops),
                    "timestamp": int(time.time()),
                    "time": float(time.time())
                }

            log_status(f"[PEER DISCOVERED] {dest_hex[:8]} ({name}) [{hops} hops]")

            if _announce_callback:
                try:
                    _announce_callback(dest_hex, name, hops)
                except Exception:
                    pass
        except Exception as e:
            log_status(f"Announce processing error: {e}")

def get_discovered_peers():
    with _discovered_lock:
        peers = list(_discovered_peers.values())
        peers.sort(key=lambda p: p.get("timestamp", p.get("time", 0)), reverse=True)
        return peers

def get_discovered_peers_json():
    with _discovered_lock:
        peers = list(_discovered_peers.values())
        peers.sort(key=lambda p: p.get("timestamp", p.get("time", 0)), reverse=True)
        return json.dumps(peers)

def get_messages_json():
    with _messages_lock:
        return json.dumps(_messages_history)

def set_callbacks(status_cb=None, announce_cb=None, message_cb=None):
    global _status_callback, _announce_callback, _message_callback
    _status_callback = status_cb
    _announce_callback = announce_cb
    _message_callback = message_cb

def poll_events():
    global _event_queue
    with _event_lock:
        events = list(_event_queue)
        _event_queue.clear()
        return events

def init_rns(config_dir, storage_dir, display_name="Android Node"):
    global rns, router, delivery_dest, identity, identity_hash

    try:
        os.environ["HOME"] = config_dir
        # If Reticulum is already in memory, don't silently abort.
        # Ensure the config file on disk is ALWAYS freshly updated:
        os.makedirs(config_dir, exist_ok=True)
        config_path = os.path.join(config_dir, "config")
        
        with open(config_path, "w") as f:
            f.write("""[reticulum]
enable_transport = False
share_instance = No
shared_instance_type = tcp

[interfaces]
  [[LoRa_Interface]]
    type = TCPClientInterface
    enabled = yes
    target_host = 127.0.0.1
    target_port = 4243
    kiss_framing = True
""")

        if rns is not None and identity_hash:
            log_status(f"Reticulum already running: {identity_hash[:8]} (To apply new config, Force Stop app)")
            return identity_hash

        log_status("Starting Reticulum...")

        if hasattr(RNS.Reticulum, "_instance") and RNS.Reticulum._instance is not None:
            rns = RNS.Reticulum._instance
        else:
            try:
                rns = RNS.Reticulum(configdir=config_dir, loglevel=RNS.LOG_INFO)
            except OSError as oe:
                if "reinitialise" in str(oe).lower():
                    rns = getattr(RNS.Reticulum, "_instance", None)
                else:
                    raise

        log_status("Initializing LXMF Router...")
        os.makedirs(storage_dir, exist_ok=True)
        router = LXMF.LXMRouter(storagepath=storage_dir)
        
        identity = RNS.Identity()
        delivery_dest = router.register_delivery_identity(identity, display_name=display_name)
        
        def delivery_callback(message):
            try:
                sender_hash = RNS.hexrep(message.source_hash, delimit=False) if message.source_hash else "Unknown"
                content = message.content.decode("utf-8", "replace")
                now_ts = time.time()
                with _messages_lock:
                    _messages_history.append({
                        "id": str(int(now_ts * 1000)),
                        "peer": sender_hash,
                        "content": content,
                        "incoming": True,
                        "status": "Delivered",
                        "time": now_ts
                    })
                log_status(f"Message from {sender_hash[:8]}: {content}")
                if _message_callback:
                    try:
                        _message_callback(sender_hash, content)
                    except Exception:
                        pass
            except Exception as e:
                log_status(f"Error in delivery_callback: {e}")
                
        router.register_delivery_callback(delivery_callback)
        
        identity_hash = RNS.hexrep(delivery_dest.hash, delimit=False)
        log_status(f"Reticulum Ready: {identity_hash[:8]}")

        # Register AnnounceHandler to capture and surface all nearby mesh announces
        global _announce_handler
        if _announce_handler is None:
            _announce_handler = UniversalAnnounceHandler()
            RNS.Transport.register_announce_handler(_announce_handler)
            log_status("Registered global Universal Announce Handler")
        
        return identity_hash
        
    except Exception as e:
        err = traceback.format_exc()
        log_status(f"Init Error: {err}")
        return None

def announce_presence():
    global delivery_dest
    try:
        if delivery_dest:
            pkt = delivery_dest.announce(send=False)
            if pkt:
                pkt.send()
                pkt_size = len(pkt.raw) if hasattr(pkt, "raw") and pkt.raw else (len(pkt.data) if hasattr(pkt, "data") else 0)
                dest_hex = RNS.hexrep(delivery_dest.hash, delimit=False)
                log_status(f"Announce packet broadcasted ({pkt_size} bytes, dest: {dest_hex[:8]})")
                return True
            else:
                log_status("Announce failed: packet could not be created")
                return False
        else:
            log_status("Cannot announce: delivery destination not ready")
            return False
    except Exception as e:
        log_status(f"Announce Error: {e}")
        return False

# Alias for backward compatibility
announce = announce_presence

def send_message(dest_hash_hex, message_text):
    global router, identity, delivery_dest
    try:
        if not router or not identity or not delivery_dest:
            log_status("Router, Identity, or Delivery Destination not initialized")
            return False
            
        clean_hex = dest_hash_hex.strip().replace(" ", "").replace(":", "")
        dest_hash = bytes.fromhex(clean_hex)
        dest_identity = RNS.Identity.recall(dest_hash)
        
        if not dest_identity:
            log_status(f"Destination identity unknown, requesting path for {clean_hex[:8]}...")
            RNS.Transport.request_path(dest_hash)
            dest = RNS.Destination(None, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
            dest.hash = dest_hash
        else:
            dest = RNS.Destination(dest_identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
            
        msg = LXMF.LXMessage(dest, delivery_dest, message_text.encode("utf-8"), title="Message".encode("utf-8"), desired_method=LXMF.LXMessage.DIRECT)
        router.handle_outbound(msg)
        now_ts = time.time()
        with _messages_lock:
            _messages_history.append({
                "id": str(int(now_ts * 1000)),
                "peer": clean_hex,
                "content": message_text,
                "incoming": False,
                "status": "Sent",
                "time": now_ts
            })
        log_status(f"Sent message to {clean_hex[:8]}")
        return True
    except Exception as e:
        log_status(f"Send Error: {e}")
        return False
