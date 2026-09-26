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
import collections
import re

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
if _socket is not None and hasattr(_socket, "socketpair"):
    try:
        _socket.socketpair = _safe_socketpair
    except Exception:
        pass

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
_status_callback = None
_announce_callback = None
_message_callback = None

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

try:
    import RNS.vendor.umsgpack as msgpack
except Exception:
    try:
        import umsgpack as msgpack
    except Exception:
        import msgpack

if not hasattr(RNS, "pretty_hex_rep"):
    def _pretty_hex_rep(val):
        if not val:
            return ""
        rep = RNS.prettyhexrep(val) if hasattr(RNS, "prettyhexrep") else str(val)
        return rep.strip("<>")
    RNS.pretty_hex_rep = _pretty_hex_rep

# =====================================================================
# Startup Announcement & Periodic Keep-Alive Manager (Objective 1)
# =====================================================================
_keepalive_thread = None
_keepalive_stop_event = threading.Event()
_announce_lock = threading.Lock()
_startup_announce_timer = None
lxmf_destination = None

def trigger_startup_announce(delay_sec=0.5):
    """
    Hook an automated call to lxmf_destination.announce() immediately after
    the RNode BLE/Serial interface finishes initializing and transitions to active.
    Inserts a brief delay (500 ms) before the announce to ensure SX1262
    modem registers have stabilized.
    """
    global _startup_announce_timer
    with _announce_lock:
        if _startup_announce_timer is not None:
            try:
                _startup_announce_timer.cancel()
            except Exception:
                pass

        def _do_announce():
            global delivery_dest, lxmf_destination
            dest = lxmf_destination or delivery_dest
            if dest is not None:
                try:
                    pkt = dest.announce(send=False)
                    if pkt:
                        pkt.send()
                    else:
                        dest.announce()
                    dest_hash = getattr(dest, "hash", None)
                    hash_str = RNS.pretty_hex_rep(dest_hash) if dest_hash else ""
                    log_msg = f"Auto-announced destination <{hash_str}> on connection."
                    RNS.log(log_msg)
                    log_status(log_msg)
                except Exception as e:
                    log_status(f"Error in startup auto-announce: {e}")
            else:
                log_status("Startup announce deferred: destination not yet initialized")

        _startup_announce_timer = threading.Timer(delay_sec, _do_announce)
        _startup_announce_timer.daemon = True
        _startup_announce_timer.start()
        log_status(f"Auto-announce scheduled in {int(delay_sec*1000)}ms (SX1262 register settling delay)...")

def start_periodic_keepalive(interval_minutes=30):
    """
    Periodic Background Keep-Alive:
    Triggers lxmf_destination.announce() at a fixed interval (every 30 to 60 minutes)
    while the BLE/Radio interface remains connected.
    Ensures neighboring nodes do not expire Firefly's next-hop path or identity.
    """
    global _keepalive_thread, _keepalive_stop_event
    with _announce_lock:
        if _keepalive_thread is not None and _keepalive_thread.is_alive():
            _keepalive_stop_event.set()

        _keepalive_stop_event = threading.Event()
        stop_evt = _keepalive_stop_event
        interval_sec = interval_minutes * 60

        def _keepalive_loop():
            log_status(f"Periodic background keepalive active (interval: {interval_minutes}m)")
            while not stop_evt.wait(interval_sec):
                global delivery_dest, lxmf_destination
                dest = lxmf_destination or delivery_dest
                if dest is not None:
                    try:
                        pkt = dest.announce(send=False)
                        if pkt:
                            pkt.send()
                        else:
                            dest.announce()
                        dest_hash = getattr(dest, "hash", None)
                        hash_str = RNS.pretty_hex_rep(dest_hash) if dest_hash else ""
                        msg = f"Periodic keepalive announced destination <{hash_str}>"
                        RNS.log(msg)
                        log_status(msg)
                    except Exception as e:
                        log_status(f"Periodic keepalive announce error: {e}")

        _keepalive_thread = threading.Thread(target=_keepalive_loop, daemon=True)
        _keepalive_thread.start()

def stop_periodic_keepalive():
    global _keepalive_stop_event
    with _announce_lock:
        if _keepalive_stop_event is not None:
            _keepalive_stop_event.set()
        log_status("Stopped periodic background keepalive timer.")

def notify_interface_active():
    """
    Called when the RNode BLE/Serial interface finishes initializing and transitions to active.
    Waits for the BLE bridge to signal STATE_CONNECTED and confirmed active,
    inserts a 1.5-second settle delay, then dispatches the guaranteed post-connect announce.
    """
    def _post_connect_announce():
        # Ensure TCP client interface is connected immediately
        try:
            if RNS and hasattr(RNS, "Transport") and hasattr(RNS.Transport, "interfaces"):
                for iface in RNS.Transport.interfaces:
                    if isinstance(iface, (TCPKISSInterface, RNS.Interfaces.TCPInterface.TCPClientInterface)):
                        if not getattr(iface, "online", False) and not getattr(iface, "detached", False):
                            try:
                                iface.connect()
                                if getattr(iface, "online", False):
                                    with iface._reader_lock:
                                        if not getattr(iface, "_reader_running", False):
                                            t_read = threading.Thread(target=iface.read_loop, daemon=True)
                                            t_read.start()
                            except Exception:
                                pass
        except Exception:
            pass

        time.sleep(1.5)
        global delivery_dest, lxmf_destination
        dest = lxmf_destination or delivery_dest
        if dest is not None:
            try:
                dest.announce()
                dest_hash = getattr(dest, "hash", None)
                hash_str = RNS.pretty_hex_rep(dest_hash) if dest_hash else ""
                log_msg = f"Guaranteed post-connect announce sent for <{hash_str}>"
                RNS.log(log_msg)
                log_status(log_msg)
            except Exception as e:
                log_status(f"Error sending guaranteed post-connect announce: {e}")
        else:
            log_status("Post-connect announce deferred: destination not yet initialized")

    t = threading.Thread(target=_post_connect_announce, daemon=True)
    t.start()
    start_periodic_keepalive(interval_minutes=30)

def disconnect_active_tcp_interface():
    try:
        if RNS and hasattr(RNS, "Transport") and hasattr(RNS.Transport, "interfaces"):
            for iface in RNS.Transport.interfaces:
                if isinstance(iface, (TCPKISSInterface, RNS.Interfaces.TCPInterface.TCPClientInterface)):
                    if getattr(iface, "socket", None):
                        try:
                            iface.socket.shutdown(socket.SHUT_RDWR)
                        except Exception:
                            pass
                        try:
                            iface.socket.close()
                        except Exception:
                            pass
                        iface.socket = None
                    iface.online = False
    except Exception as e:
        log_status(f"Error disconnecting active TCP interface: {e}")

def notify_interface_inactive():
    """
    Called when the RNode interface disconnects.
    """
    stop_periodic_keepalive()
    disconnect_active_tcp_interface()


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
        self._reader_running = False
        self._reader_generation = 0
        self._active_reader_thread = None
        self._reader_lock = threading.Lock()
        super().__init__(owner, c)
        self.HW_MTU = 508
        if "txpower" in c:
            try:
                self.txpower = int(c["txpower"])
            except Exception:
                self.txpower = 17
        else:
            self.txpower = 17

    def connect(self, initial=False):
        return super().connect(initial)

    def read_loop(self):
        with self._reader_lock:
            if getattr(self, "_reader_running", False) and getattr(self, "_active_reader_thread", None) is not None:
                if self._active_reader_thread.is_alive() and self._active_reader_thread != threading.current_thread():
                    return
            self._reader_generation += 1
            gen = self._reader_generation
            self._reader_running = True
            self._active_reader_thread = threading.current_thread()

        try:
            in_frame = False
            escape = False
            command = 0xFF
            data_buffer = b""
            frame_overflow = False

            while self._reader_running and self._reader_generation == gen and self.socket:
                try:
                    data_in = self.socket.recv(4096)
                    if not data_in:
                        break
                except Exception:
                    break

                if getattr(self, "kiss_framing", True):
                    pointer = 0
                    while pointer < len(data_in):
                        byte = data_in[pointer]
                        pointer += 1
                        if in_frame and byte == 0xC0 and command != 0xFF:
                            # Closing FEND: deliver only if not terminated mid-escape, not overflowed, and non-empty
                            if command == 0x00 and not escape and not frame_overflow and len(data_buffer) > 0:
                                self.process_incoming(data_buffer)
                            in_frame = False
                            escape = False
                            frame_overflow = False
                            data_buffer = b""
                            command = 0xFF
                        elif byte == 0xC0:
                            # Opening FEND or back-to-back sync FEND
                            in_frame = True
                            command = 0xFF
                            escape = False
                            frame_overflow = False
                            data_buffer = b""
                        elif in_frame:
                            if command == 0xFF:
                                command = byte & 0x0F
                                escape = False
                            elif command == 0x00:
                                if len(data_buffer) >= self.HW_MTU:
                                    frame_overflow = True
                                elif byte == 0xDB:
                                    escape = True
                                elif escape:
                                    if byte == 0xDC:
                                        data_buffer += b"\xC0"
                                    elif byte == 0xDD:
                                        data_buffer += b"\xDB"
                                    else:
                                        data_buffer += bytes([byte])
                                    escape = False
                                else:
                                    data_buffer += bytes([byte])
                else:
                    self.process_incoming(data_in)

        except Exception as e:
            log_status(f"Exception in TCP interface read_loop: {e}")
        finally:
            with self._reader_lock:
                if self._reader_generation == gen:
                    self._reader_running = False
                    self._active_reader_thread = None
                    self.online = False
                    if self.socket:
                        try:
                            self.socket.shutdown(socket.SHUT_RDWR)
                        except Exception:
                            pass
                        try:
                            self.socket.close()
                        except Exception:
                            pass
                        self.socket = None

            if self.initiator and not getattr(self, "detached", False):
                threading.Thread(target=self.managed_reconnect, daemon=True).start()

    def managed_reconnect(self):
        with self._reader_lock:
            if getattr(self, "reconnecting", False) or getattr(self, "_reader_running", False):
                return
            self.reconnecting = True

        try:
            attempts = 0
            while not getattr(self, "online", False) and not getattr(self, "detached", False):
                time.sleep(RNS.Interfaces.TCPInterface.TCPClientInterface.RECONNECT_WAIT)
                attempts += 1
                if self.max_reconnect_tries is not None and attempts > self.max_reconnect_tries:
                    self.teardown()
                    break
                try:
                    self.connect()
                except Exception:
                    pass

            if getattr(self, "online", False):
                with self._reader_lock:
                    if not getattr(self, "_reader_running", False):
                        thread = threading.Thread(target=self.read_loop, daemon=True)
                        thread.start()
        finally:
            self.reconnecting = False

    def teardown(self):
        with self._reader_lock:
            self._reader_running = False
            self._reader_generation += 1
            self._active_reader_thread = None
            self.online = False
            if self.socket:
                try:
                    self.socket.shutdown(socket.SHUT_RDWR)
                except Exception:
                    pass
                try:
                    self.socket.close()
                except Exception:
                    pass
                self.socket = None
        try:
            stop_periodic_keepalive()
        except Exception:
            pass
        super().teardown()

    def set_tx_power(self, tx_power_dbm):
        clamped = max(0, min(22, int(tx_power_dbm)))
        self.txpower = clamped
        if self.online and self.socket:
            try:
                kiss_cmd = bytes([0xC0, 0x03, clamped, 0xC0])
                self.socket.sendall(kiss_cmd)
                log_status(f"KISS 0x03 TX power {clamped} dBm sent over TCPKISSInterface socket")
            except Exception as e:
                log_status(f"Error sending KISS TX power over socket: {e}")
        return clamped

RNS.Interfaces.KISSInterface.KISSInterface = TCPKISSInterface
if _has_android_kiss:
    RNS.Interfaces.Android.KISSInterface.KISSInterface = TCPKISSInterface

_orig_tcp_init = RNS.Interfaces.TCPInterface.TCPClientInterface.__init__
def _patched_tcp_init(self, owner, configuration):
    c = RNS.Interfaces.Interface.Interface.get_config_obj(configuration)
    if str(c.get("target_port")) == "4243" or c.get("target_host") == "127.0.0.1":
        c["kiss_framing"] = "True"
    self._reader_running = False
    self._reader_generation = 0
    self._reader_lock = threading.Lock()
    _orig_tcp_init(self, owner, c)
    if str(c.get("target_port")) == "4243" or c.get("target_host") == "127.0.0.1":
        self.kiss_framing = True
        self.HW_MTU = 508
    if "txpower" in c:
        try:
            self.txpower = int(c["txpower"])
        except Exception:
            self.txpower = 17
    else:
        self.txpower = 17

_orig_tcp_connect = RNS.Interfaces.TCPInterface.TCPClientInterface.connect
def _patched_tcp_connect(self, initial=False):
    return _orig_tcp_connect(self, initial)

def _iface_set_tx_power(self, tx_power_dbm):
    clamped = max(0, min(22, int(tx_power_dbm)))
    self.txpower = clamped
    if getattr(self, "online", False) and getattr(self, "socket", None):
        try:
            kiss_cmd = bytes([0xC0, 0x03, clamped, 0xC0])
            self.socket.sendall(kiss_cmd)
            log_status(f"KISS 0x03 TX power {clamped} dBm sent to socket")
        except Exception as e:
            log_status(f"Error sending KISS TX power: {e}")
    return clamped

RNS.Interfaces.TCPInterface.TCPClientInterface.__init__ = _patched_tcp_init
RNS.Interfaces.TCPInterface.TCPClientInterface.connect = _patched_tcp_connect
RNS.Interfaces.TCPInterface.TCPClientInterface.read_loop = TCPKISSInterface.read_loop
RNS.Interfaces.TCPInterface.TCPClientInterface.managed_reconnect = TCPKISSInterface.managed_reconnect
RNS.Interfaces.TCPInterface.TCPClientInterface.reconnect = TCPKISSInterface.managed_reconnect
RNS.Interfaces.TCPInterface.TCPClientInterface.teardown = TCPKISSInterface.teardown
RNS.Interfaces.TCPInterface.TCPClientInterface.set_tx_power = _iface_set_tx_power

if hasattr(RNS.Interfaces, "RNodeInterface") and hasattr(RNS.Interfaces.RNodeInterface, "RNodeInterface"):
    def _rnode_iface_set_tx(self, tx_power_dbm):
        clamped = max(0, min(22, int(tx_power_dbm)))
        self.txpower = clamped
        if hasattr(self, "setTXPower"):
            self.setTXPower()
        elif hasattr(self, "serial") and self.serial:
            kiss_cmd = bytes([0xC0, 0x03, clamped, 0xC0])
            self.serial.write(kiss_cmd)
        return clamped
    RNS.Interfaces.RNodeInterface.RNodeInterface.set_tx_power = _rnode_iface_set_tx

_current_tx_power = 17

def set_radio_tx_power(tx_power_dbm: int):
    """
    Dynamic KISS TX Power Control (Objective 1):
    Clamps the value (0–22 dBm), locates the active RNodeInterface / kiss_framing interface,
    and invokes interface.set_tx_power(tx_power_dbm) (KISS command 0x03) without needing a node reboot.
    Exposed to Kotlin via Chaquopy.
    """
    global _current_tx_power
    try:
        clamped = max(0, min(22, int(tx_power_dbm)))
        _current_tx_power = clamped
        log_status(f"Setting RNode TX power to {clamped} dBm (KISS 0x03)...")

        found = False
        if RNS and hasattr(RNS, "Transport") and hasattr(RNS.Transport, "interfaces"):
            for iface in RNS.Transport.interfaces:
                iface_str = str(iface).lower()
                is_rnode = (
                    "rnode" in iface_str or
                    "lora" in iface_str or
                    getattr(iface, "kiss_framing", False) or
                    isinstance(iface, (TCPKISSInterface, RNS.Interfaces.TCPInterface.TCPClientInterface)) or
                    (hasattr(RNS.Interfaces, "RNodeInterface") and isinstance(iface, RNS.Interfaces.RNodeInterface.RNodeInterface))
                )
                if is_rnode:
                    if hasattr(iface, "set_tx_power"):
                        try:
                            iface.set_tx_power(clamped)
                            found = True
                        except Exception as e:
                            log_status(f"Error in interface.set_tx_power: {e}")
                    elif hasattr(iface, "setTXPower"):
                        try:
                            iface.txpower = clamped
                            iface.setTXPower()
                            found = True
                        except Exception as e:
                            log_status(f"Error in interface.setTXPower: {e}")
                    elif hasattr(iface, "socket") and iface.socket:
                        try:
                            kiss_cmd = bytes([0xC0, 0x03, clamped, 0xC0])
                            iface.socket.sendall(kiss_cmd)
                            found = True
                        except Exception as e:
                            log_status(f"Error sending KISS to socket: {e}")

        log_status(f"RNode TX power set to {clamped} dBm (Active interface updated: {found})")
        return clamped
    except Exception as e:
        log_status(f"Error setting TX power: {e}")
        return _current_tx_power

def get_radio_tx_power():
    global _current_tx_power
    return _current_tx_power

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

_announce_handler = None

_discovered_peers = {}
_discovered_lock = threading.Lock()

_messages_history = []
_messages_lock = threading.Lock()
_new_dms_queue = []
_new_dms_lock = threading.Lock()

_channel_feed_history = []
_new_channel_queue = []
_channel_lock = threading.Lock()

_processed_message_ids = collections.OrderedDict()
_msg_cache_lock = threading.Lock()

rns = None
router = None
delivery_dest = None
identity = None
identity_hash = ""

def normalize_dest_hash(h):
    if not h:
        return ""
    if isinstance(h, (bytes, bytearray)):
        return h.hex().lower()
    return str(h).replace("<", "").replace(">", "").replace(":", "").replace(" ", "").strip().lower()

_active_probe_links = set()
_last_probe_times = {}

def sync_peer(peer_destination_hash):
    """
    Client-side active link probes and propagation sync requests removed.
    Stump team has patched rrc.py; active link probes and propagation sync requests
    are no longer required and congested the 915 MHz frequency.
    """
    try:
        if not peer_destination_hash:
            return
        clean_hex = normalize_dest_hash(peer_destination_hash)
        if not clean_hex:
            return
        if len(clean_hex) % 2 != 0:
            clean_hex = "0" + clean_hex
        try:
            dest_bytes = bytes.fromhex(clean_hex)
        except ValueError:
            return
        if len(dest_bytes) == 16:
            if not RNS.Identity.recall(dest_bytes) and not RNS.Transport.has_path(dest_bytes):
                RNS.Transport.request_path(dest_bytes)
    except Exception:
        pass

def probe_known_peers():
    """Client-side active link probes removed to prevent 915 MHz frequency congestion."""
    pass

_verified_stump_hashes = set()
_verified_stump_lock = threading.Lock()

class StumpBeacons:
    aspect_filter = "stump.node"

    def received_announce(self, destination_hash, announced_identity, app_data):
        try:
            if not app_data:
                return
            try:
                unpacked = msgpack.unpackb(app_data, raw=False)
            except Exception:
                unpacked = msgpack.unpackb(app_data)
            # app_data format from Stump quickstart: ["stump", version, name, lxmf_hash]
            if isinstance(unpacked, (list, tuple)) and len(unpacked) >= 4:
                tag, version, name, lxmf_hash = unpacked[0], unpacked[1], unpacked[2], unpacked[3]
                if isinstance(tag, (bytes, bytearray)):
                    tag = tag.decode("utf-8", "ignore")
                if str(tag).strip() != "stump":
                    return
                if isinstance(name, (bytes, bytearray)):
                    name = name.decode("utf-8", "ignore")
                if isinstance(lxmf_hash, (bytes, bytearray)):
                    lxmf_hex = RNS.hexrep(lxmf_hash, delimit=False).lower()
                else:
                    lxmf_hex = normalize_dest_hash(str(lxmf_hash)).lower()

                if lxmf_hex:
                    with _verified_stump_lock:
                        _verified_stump_hashes.add(lxmf_hex)
                        if len(lxmf_hex) >= 8:
                            _verified_stump_hashes.add(lxmf_hex[:8])

                    log_status(f"[STUMP BEACON] Verified Stump node: {name} (v{version}) -> LXMF {lxmf_hex[:8]}")
                    try:
                        from com.example import MeshService
                        MeshService.registerStumpHash(lxmf_hex)
                    except Exception:
                        pass
        except Exception as e:
            log_status(f"[STUMP BEACON] Error unpacking beacon: {e}")

_stump_beacon_handler = None

def is_verified_stump(hash_val):
    if not hash_val:
        return False
    clean = normalize_dest_hash(str(hash_val))
    if clean == "stump-dm" or clean == "stump-lxmf":
        return True
    with _verified_stump_lock:
        if clean in _verified_stump_hashes:
            return True
        for h in _verified_stump_hashes:
            if clean == h or clean.startswith(h) or (len(clean) >= 8 and h.startswith(clean[:8])):
                return True
    return False

def get_verified_stump_hashes():
    with _verified_stump_lock:
        return list(_verified_stump_hashes)

def get_live_stump_hash():
    with _verified_stump_lock:
        for h in reversed(list(_verified_stump_hashes)):
            if len(h) == 32:
                return h
    with _discovered_lock:
        for peer_hash in _discovered_peers.keys():
            norm = normalize_dest_hash(peer_hash)
            if is_verified_stump(norm) and len(norm) == 32:
                return norm
    return None

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

            # When an identity announces, proactively query if it has a stump.node beacon aspect
            if announced_identity:
                try:
                    beacon_hash = RNS.Destination.hash(announced_identity, "stump", "node")
                    RNS.Transport.request_path(beacon_hash)
                except Exception:
                    pass

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

def get_all_messages():
    with _messages_lock:
        return list(_messages_history)

def poll_new_dms_json():
    global _new_dms_queue
    with _new_dms_lock:
        dms = list(_new_dms_queue)
        _new_dms_queue.clear()
        return json.dumps(dms)

def poll_new_channel_msgs_json():
    global _new_channel_queue
    with _channel_lock:
        msgs = list(_new_channel_queue)
        _new_channel_queue.clear()
        return json.dumps(msgs)

def get_channel_messages_json():
    with _channel_lock:
        return json.dumps(_channel_feed_history)

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

_rns_init_lock = threading.Lock()

def init_rns(config_dir, storage_dir, display_name="Android Node", tx_power=17):
    global rns, router, delivery_dest, lxmf_destination, identity, identity_hash, _current_tx_power

    _rns_init_lock.acquire()
    try:
        if (hasattr(sys, "_firefly_rns_instance") and sys._firefly_rns_instance is not None) or rns is not None:
            rns = sys._firefly_rns_instance or rns
            curr_hash = getattr(sys, "_firefly_identity_hash", None) or identity_hash
            if curr_hash:
                identity_hash = curr_hash
                delivery_dest = getattr(sys, "_firefly_lxmf_destination", delivery_dest)
                lxmf_destination = delivery_dest
                router = getattr(sys, "_firefly_lxmf_router", router)
                log_status(f"[PYTHON RNS] Reticulum singleton already active. Suppressing secondary startup call ({identity_hash[:8]}).")
                return identity_hash
            elif identity_hash:
                log_status(f"[PYTHON RNS] Reticulum singleton already active. Suppressing secondary startup call ({identity_hash[:8]}).")
                return identity_hash

        if rns is not None and identity_hash:
            sys._firefly_rns_instance = rns
            sys._firefly_identity_hash = identity_hash
            sys._firefly_lxmf_destination = delivery_dest
            sys._firefly_lxmf_router = router
            lxmf_destination = delivery_dest
            log_status(f"Reticulum already running: {identity_hash[:8]} (To apply new config, Force Stop app)")
            return identity_hash

        os.environ["HOME"] = config_dir
        tx_p = max(0, min(22, int(tx_power) if tx_power is not None else 17))
        _current_tx_power = tx_p
        # If Reticulum is already in memory, don't silently abort.
        # Ensure the config file on disk is ALWAYS freshly updated:
        os.makedirs(config_dir, exist_ok=True)
        config_path = os.path.join(config_dir, "config")
        
        with open(config_path, "w") as f:
            f.write(f"""[reticulum]
enable_transport = False
share_instance = No
shared_instance_type = tcp

[interfaces]
  [[RNode Interface]]
    type = TCPClientInterface
    enabled = yes
    target_host = 127.0.0.1
    target_port = 4243
    kiss_framing = True
    txpower = {tx_p}
""")

        # Clean up stale lockfiles from previous crashes or force-stops
        lock_names = [".lock", "reticulum.lock", "storage.lock"]
        for sdir in [config_dir, os.path.join(config_dir, "storage"), storage_dir, os.path.join(storage_dir, "storage")]:
            if os.path.exists(sdir):
                for lock_name in lock_names:
                    lock_path = os.path.join(sdir, lock_name)
                    if os.path.exists(lock_path):
                        try:
                            os.remove(lock_path)
                            log_status(f"[RNS INIT] Removed orphaned lockfile: {lock_path}")
                        except Exception as e:
                            log_status(f"[RNS INIT] Failed to remove {lock_path}: {e}")

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

        # Immediately set singleton instance before any interfaces or routers are spawned
        sys._firefly_rns_instance = rns

        log_status("Initializing LXMF Router...")
        os.makedirs(storage_dir, exist_ok=True)
        router = LXMF.LXMRouter(storagepath=storage_dir)
        
        # Check for persistent identity in storage_dir or its parent directory (context.filesDir)
        parent_dir = os.path.dirname(storage_dir)
        candidate_paths = [
            os.path.join(storage_dir, "firefly_identity"),
            os.path.join(parent_dir, "firefly_identity"),
        ]
        identity_path = None
        for p in candidate_paths:
            if os.path.exists(p):
                identity_path = p
                break
        if identity_path is None:
            identity_path = candidate_paths[0]

        identity = None
        if os.path.exists(identity_path):
            try:
                if os.path.getsize(identity_path) > 0:
                    identity = RNS.Identity.from_file(identity_path)
                    log_status(f"[RNS INIT] Loaded persistent identity from {identity_path}")
                else:
                    os.remove(identity_path)
            except Exception as e:
                log_status(f"[RNS INIT] Error reading identity file {identity_path}: {e}")
                try:
                    os.remove(identity_path)
                except Exception:
                    pass

        if identity is None:
            identity = RNS.Identity()
            try:
                identity.to_file(identity_path)
                log_status(f"[RNS INIT] Generated and saved new identity to {identity_path}")
            except Exception as e:
                log_status(f"[RNS INIT] Failed to save identity to disk: {e}")
        delivery_dest = router.register_delivery_identity(identity, display_name=display_name)
        lxmf_destination = delivery_dest
        
        def delivery_callback(message):
            try:
                # Deduplicate message using transient message ID cache
                msg_hash_bytes = getattr(message, "hash", None)
                if msg_hash_bytes:
                    msg_id = RNS.hexrep(msg_hash_bytes, delimit=False).lower()
                elif hasattr(message, "signature") and message.signature:
                    msg_id = RNS.hexrep(message.signature, delimit=False).lower()
                else:
                    msg_id = f"{normalize_dest_hash(getattr(message, 'source_hash', None))}_{hash(str(getattr(message, 'content', '')))}"

                with _msg_cache_lock:
                    if msg_id in _processed_message_ids:
                        log_status(f"[LXMF] Duplicate LXMessage {msg_id[:12]} suppressed.")
                        return
                    _processed_message_ids[msg_id] = True
                    if len(_processed_message_ids) > 500:
                        try:
                            _processed_message_ids.popitem(last=False)
                        except KeyError:
                            pass

                sender_hash = normalize_dest_hash(message.source_hash) if message.source_hash else "unknown"
                content_obj = getattr(message, "content", b"")
                if isinstance(content_obj, (bytes, bytearray)):
                    raw_content = content_obj.decode("utf-8", "replace")
                elif content_obj is None:
                    raw_content = ""
                else:
                    raw_content = str(content_obj)
                now_ts = time.time()

                # Check destination hash and packet transport metadata
                dest_obj = getattr(message, "destination", None)
                dest_type = getattr(dest_obj, "type", None) if dest_obj else None
                dest_h = getattr(message, "destination_hash", None) or (getattr(dest_obj, "hash", None) if dest_obj else None)
                dest_hash = normalize_dest_hash(dest_h)
                our_hash = normalize_dest_hash(delivery_dest.hash) if delivery_dest else ""
                method = getattr(message, "method", None)

                # Direct Link vs Group / Channel identification
                is_direct_link = (method == LXMF.LXMessage.DIRECT) or (dest_type == RNS.Destination.LINK) or (getattr(message, "transport_id", None) is not None) or (getattr(message, "link", None) is not None)
                is_addressed_to_us = (dest_hash == our_hash) or (dest_type == RNS.Destination.SINGLE)
                is_group_or_topic = (dest_type == RNS.Destination.GROUP) or (dest_type == RNS.Destination.PLAIN)
                is_room_action = raw_content.startswith("* ")  # e.g., "* guest-f70e a rejoint"

                # All incoming LXMF packets delivered to our node are direct 1-1 messages
                content = raw_content
                dm_record = {
                    "id": msg_id,
                    "peer": sender_hash,
                    "sender": sender_hash,
                    "recipient": our_hash,
                    "content": content,
                    "incoming": True,
                    "status": "Direct Message",
                    "time": now_ts,
                    "is_link": is_direct_link,
                    "is_private": True,
                    "type": "dm"
                }
                with _messages_lock:
                    _messages_history.append(dm_record)
                    if len(_messages_history) > 1000:
                        _messages_history.pop(0)
                with _new_dms_lock:
                    _new_dms_queue.append(dm_record)
                    if len(_new_dms_queue) > 500:
                        _new_dms_queue.pop(0)
                log_status(f"[LXMF 1-1] from {sender_hash[:8]}: {content}")

                try:
                    from com.example import MeshService
                    MeshService.onIncomingDirectMessage(sender_hash, content, is_direct_link, msg_id, True)
                except Exception:
                    try:
                        from com.example import MeshService
                        MeshService.onIncomingDirectMessage(sender_hash, content, is_direct_link, msg_id)
                    except Exception:
                        try:
                            from com.example import MeshService
                            MeshService.onIncomingDirectMessage(sender_hash, content, is_direct_link)
                        except Exception:
                            pass

                if _message_callback:
                    try:
                        _message_callback(sender_hash, content)
                    except Exception:
                        pass
            except Exception as e:
                err_tb = traceback.format_exc()
                log_status(f"Error in delivery_callback: {e}\n{err_tb}")
                
        router.register_delivery_callback(delivery_callback)
        
        identity_hash = RNS.hexrep(delivery_dest.hash, delimit=False)
        sys._firefly_rns_instance = rns
        sys._firefly_identity_hash = identity_hash
        sys._firefly_lxmf_destination = delivery_dest
        sys._firefly_lxmf_router = router
        log_status(f"Reticulum Ready: {identity_hash[:8]}")

        # Register AnnounceHandler to capture and surface all nearby mesh announces
        global _announce_handler, _stump_beacon_handler
        if _announce_handler is None:
            _announce_handler = UniversalAnnounceHandler()
            RNS.Transport.register_announce_handler(_announce_handler)
            log_status("Registered global Universal Announce Handler")

        if _stump_beacon_handler is None:
            _stump_beacon_handler = StumpBeacons()
            RNS.Transport.register_announce_handler(_stump_beacon_handler)
            log_status("Registered StumpBeacons announce handler (aspect: stump.node)")

        return identity_hash
        
    except Exception as e:
        err = traceback.format_exc()
        log_status(f"Init Error: {err}")
        return None
    finally:
        try:
            _rns_init_lock.release()
        except RuntimeError:
            pass

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

_last_sent_cache = {}
_send_lock = threading.Lock()

def _do_send_lxmf(dest_hash_hex, message_text):
    global router, identity, delivery_dest
    try:
        if not router or not identity or not delivery_dest:
            log_status("Router, Identity, or Delivery Destination not initialized")
            return False
            
        clean_hex = normalize_dest_hash(dest_hash_hex)
        if not clean_hex:
            log_status("Send Error: Invalid or empty destination hash")
            return False
        if len(clean_hex) < 32:
            resolved = None
            with _discovered_lock:
                for peer_hash in _discovered_peers:
                    norm = normalize_dest_hash(peer_hash)
                    if norm.startswith(clean_hex) and len(norm) == 32:
                        resolved = norm
                        break
            if resolved and len(resolved) == 32:
                clean_hex = resolved

        if len(clean_hex) != 32:
            log_status(f"Send Error: destination hash must be 16 bytes (32 hex characters), got {len(clean_hex)} hex characters ({clean_hex}). Awaiting announcement from peer/node.")
            return False

        try:
            dest_bytes = bytes.fromhex(clean_hex)
        except ValueError as ve:
            log_status(f"Send Error: Non-hex characters in destination hash: {ve}")
            return False

        dest_identity = RNS.Identity.recall(dest_bytes)
        if dest_identity:
            dest = RNS.Destination(dest_identity, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
        else:
            dest = RNS.Destination(None, RNS.Destination.OUT, RNS.Destination.SINGLE, "lxmf", "delivery")
            dest.hash = dest_bytes
            RNS.Transport.request_path(dest_bytes)
            
        msg = LXMF.LXMessage(dest, delivery_dest, message_text.encode("utf-8"), title="Message".encode("utf-8"), desired_method=LXMF.LXMessage.DIRECT)
        router.handle_outbound(msg)
        now_ts = time.time()
        msg_id = str(int(now_ts * 1000))
        our_hash = normalize_dest_hash(delivery_dest.hash) if delivery_dest else ""
        dm_record = {
            "id": msg_id,
            "peer": clean_hex.lower(),
            "sender": our_hash,
            "recipient": clean_hex.lower(),
            "content": message_text,
            "incoming": False,
            "status": "Sent",
            "time": now_ts,
            "is_link": False,
            "type": "dm"
        }
        with _messages_lock:
            _messages_history.append(dm_record)
            if len(_messages_history) > 1000:
                _messages_history.pop(0)
        with _new_dms_lock:
            _new_dms_queue.append(dm_record)
            if len(_new_dms_queue) > 500:
                _new_dms_queue.pop(0)
        log_status(f"Sent message to {clean_hex[:8]}")
        return True
    except Exception as e:
        log_status(f"Send Error: {e}")
        return False

def send_lxmf_message(destination_hash_hex, message_content):
    """Thread-safe outbound dispatch with strict 2-second payload debounce."""
    global _last_sent_cache
    now = time.time()
    dest_str = str(destination_hash_hex).lower() if destination_hash_hex else ""
    cache_key = f"{dest_str}:{message_content}"
    
    with _send_lock:
        if cache_key in _last_sent_cache and (now - _last_sent_cache[cache_key]) < 2.0:
            suppress_msg = f"[SEND SUPPRESSED] Duplicate outbound call for payload '{message_content}' within 2.0s ignored."
            try:
                RNS.log(suppress_msg)
            except Exception:
                pass
            log_status(suppress_msg)
            return False
        _last_sent_cache[cache_key] = now
        
        # Prune old cache entries
        _last_sent_cache = {k: v for k, v in _last_sent_cache.items() if now - v < 10.0}

        # Proceed with actual LXMF message creation and link transfer inside lock
        return _do_send_lxmf(destination_hash_hex, message_content)

send_message = send_lxmf_message
