"""
rns_service.py - Project Firefly RNS & LXMF Service Lifecycle Manager.

Provides hooks and re-exports for Reticulum and LXMF interfaces, automated startup announces,
and periodic background keep-alive beacons.
"""

import sys
import threading
import os
import time
import RNS
import rns_core
from rns_core import (
    init_rns,
    announce_presence,
    trigger_startup_announce,
    start_periodic_keepalive,
    stop_periodic_keepalive,
    notify_interface_active,
    notify_interface_inactive,
    set_radio_tx_power,
    get_radio_tx_power,
    send_message,
    send_lxmf_message,
    sync_peer,
    probe_known_peers,
    get_all_messages,
    get_discovered_peers,
    poll_events,
    log_status,
    UniversalAnnounceHandler,
    delivery_dest,
    lxmf_destination,
)

# Check if an instance already exists anywhere in the process namespace
if hasattr(sys, "_firefly_rns_instance") and sys._firefly_rns_instance is not None:
    print("[PYTHON RNS] Returning existing process-wide Reticulum singleton.")
else:
    sys._firefly_rns_instance = None

_rns_service_lock = threading.Lock()
lxmf_router = getattr(sys, "_firefly_lxmf_router", None)
lxmf_destination = getattr(sys, "_firefly_lxmf_destination", None)

def get_or_create_identity(storage_path):
    """
    Persist the Node Identity to Disk.
    Checks for a stored identity file in the app's private files directory:
    storage_path/firefly_identity or parent_dir/firefly_identity.
    Loads it if present, or instantiates a new one and saves it.
    """
    parent_dir = os.path.dirname(storage_path)
    candidate_paths = [
        os.path.join(storage_path, "firefly_identity"),
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
            else:
                os.remove(identity_path)
        except Exception as e:
            print(f"[RNS IDENTITY] Error reading identity file {identity_path}: {e}")
            try:
                os.remove(identity_path)
            except Exception:
                pass

    if identity is None:
        identity = RNS.Identity()
        try:
            identity.to_file(identity_path)
        except Exception as e:
            print(f"[RNS IDENTITY] Failed to save identity to {identity_path}: {e}")
    return identity

def load_or_create_identity(storage_path):
    return get_or_create_identity(storage_path)

def start_reticulum_service(storage_path):
    global lxmf_router, lxmf_destination
    
    with _rns_service_lock:
        if sys._firefly_rns_instance is not None:
            print("[PYTHON RNS] Reticulum singleton already active. Suppressing secondary startup call.")
            return sys._firefly_rns_instance, lxmf_destination

        print("[PYTHON RNS] Booting primary Reticulum engine...")
        # 1. Clean up stale lockfiles from previous crashes or force-stops
        lock_names = [".lock", "reticulum.lock", "storage.lock"]
        search_dirs = [storage_path, os.path.join(storage_path, "storage")]
        for sdir in search_dirs:
            if os.path.exists(sdir):
                for lock_name in lock_names:
                    lock_path = os.path.join(sdir, lock_name)
                    if os.path.exists(lock_path):
                        try:
                            os.remove(lock_path)
                            print(f"[RNS INIT] Removed orphaned lockfile: {lock_path}")
                        except Exception as e:
                            print(f"[RNS INIT] Failed to remove {lock_path}: {e}")

        sys._firefly_rns_instance = RNS.Reticulum(storagepath=storage_path)
        
        identity = load_or_create_identity(storage_path)
        try:
            import LXMF
            lxmf_router = LXMF.LXMRouter(identity=identity, storagepath=storage_path)
            lxmf_destination = lxmf_router.register_delivery_identity(identity, display_name="Firefly")
            sys._firefly_lxmf_router = lxmf_router
            sys._firefly_lxmf_destination = lxmf_destination
        except Exception as e:
            print(f"[PYTHON RNS] Error initializing LXMF: {e}")

        return sys._firefly_rns_instance, lxmf_destination

def init_reticulum(storage_path):
    """
    Initialize Reticulum with lockfile cleanup and safe identity persistence.
    Guarded to execute once and only once across process namespace.
    """
    rns_instance, _ = start_reticulum_service(storage_path)
    identity = load_or_create_identity(storage_path)
    return rns_instance, identity

# Alias for compatibility
start_reticulum = start_reticulum_service

# Explicitly export key functions
__all__ = [
    "init_rns",
    "init_reticulum",
    "start_reticulum_service",
    "start_reticulum",
    "get_or_create_identity",
    "load_or_create_identity",
    "announce_presence",
    "trigger_startup_announce",
    "start_periodic_keepalive",
    "stop_periodic_keepalive",
    "notify_interface_active",
    "notify_interface_inactive",
    "set_radio_tx_power",
    "get_radio_tx_power",
    "send_message",
    "send_lxmf_message",
    "sync_peer",
    "probe_known_peers",
    "get_all_messages",
    "get_discovered_peers",
    "poll_events",
    "log_status",
    "UniversalAnnounceHandler",
    "delivery_dest",
    "lxmf_destination",
]
