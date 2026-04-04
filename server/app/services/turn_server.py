"""Minimal TURN/STUN server (RFC 5389 / RFC 5766) for local development.

Provides just enough TURN functionality for WebRTC ICE to establish
media relay between two Android emulators on the same host.

NOT for production use — no TLS, basic auth, minimal validation.
"""

import asyncio
import base64
import hashlib
import hmac
import logging
import os
import struct
import time
from typing import Dict, Optional, Tuple

logger = logging.getLogger(__name__)

# ── STUN constants ──────────────────────────────────────────────────
MAGIC_COOKIE = 0x2112A442
MAGIC_COOKIE_BYTES = struct.pack("!I", MAGIC_COOKIE)
HEADER_SIZE = 20

# Message types
BINDING_REQUEST = 0x0001
BINDING_RESPONSE = 0x0101
ALLOCATE_REQUEST = 0x0003
ALLOCATE_RESPONSE = 0x0103
ALLOCATE_ERROR = 0x0113
REFRESH_REQUEST = 0x0004
REFRESH_RESPONSE = 0x0104
CREATE_PERM_REQUEST = 0x0008
CREATE_PERM_RESPONSE = 0x0108
CHANNEL_BIND_REQUEST = 0x0009
CHANNEL_BIND_RESPONSE = 0x0109
SEND_INDICATION = 0x0016
DATA_INDICATION = 0x0017

# Attributes
ATTR_MAPPED_ADDRESS = 0x0001
ATTR_USERNAME = 0x0006
ATTR_MESSAGE_INTEGRITY = 0x0008
ATTR_ERROR_CODE = 0x0009
ATTR_CHANNEL_NUMBER = 0x000C
ATTR_LIFETIME = 0x000D
ATTR_XOR_PEER_ADDRESS = 0x0012
ATTR_DATA = 0x0013
ATTR_REALM = 0x0014
ATTR_NONCE = 0x0015
ATTR_XOR_RELAYED_ADDRESS = 0x0016
ATTR_REQUESTED_TRANSPORT = 0x0019
ATTR_XOR_MAPPED_ADDRESS = 0x0020
ATTR_SOFTWARE = 0x8022
ATTR_FINGERPRINT = 0x8028

# Config (loaded from environment via app.config)
from app.config import TURN_SECRET
REALM = "chatcontroll"
TURN_LIFETIME = 600
RELAY_PORT_MIN = 49152
RELAY_PORT_MAX = 65535
MAX_NONCES = 10_000
MAX_ALLOCATIONS_PER_USER = 3
TURN_PORT = 3478


def _long_term_key(username: str, realm: str, password: str) -> bytes:
    return hashlib.md5(f"{username}:{realm}:{password}".encode()).digest()


def _ephemeral_password(username: str) -> str:
    """Compute the ephemeral TURN password for the given username.

    coturn --use-auth-secret mode: password = base64(HMAC-SHA1(secret, username))
    where username = "<expiry_timestamp>:<user_id>".
    """
    mac = hmac.new(TURN_SECRET.encode(), username.encode(), hashlib.sha1).digest()
    return base64.b64encode(mac).decode()



# ── STUN message helpers ────────────────────────────────────────────

def _parse_header(data: bytes) -> Optional[Tuple[int, int, bytes]]:
    if len(data) < HEADER_SIZE:
        return None
    msg_type, msg_len = struct.unpack_from("!HH", data, 0)
    cookie = struct.unpack_from("!I", data, 4)[0]
    if cookie != MAGIC_COOKIE:
        return None
    txn_id = data[8:20]
    return msg_type, msg_len, txn_id


def _parse_attrs(data: bytes) -> Dict[int, bytes]:
    attrs: Dict[int, bytes] = {}
    offset = HEADER_SIZE
    while offset + 4 <= len(data):
        attr_type, attr_len = struct.unpack_from("!HH", data, offset)
        offset += 4
        if offset + attr_len > len(data):
            break
        attrs[attr_type] = data[offset:offset + attr_len]
        offset += attr_len
        # Padding to 4-byte boundary
        offset += (4 - attr_len % 4) % 4
    return attrs


def _build_attr(attr_type: int, value: bytes) -> bytes:
    padding = (4 - len(value) % 4) % 4
    return struct.pack("!HH", attr_type, len(value)) + value + b"\x00" * padding


def _build_msg(msg_type: int, txn_id: bytes, attrs: bytes, add_integrity: bool = True, hmac_key: Optional[bytes] = None) -> bytes:
    if add_integrity:
        key = hmac_key
        if key is None:
            add_integrity = False
    if add_integrity:
        # Build message up to MESSAGE-INTEGRITY to compute HMAC
        pre_len = len(attrs) + 4 + 20  # +4 attr header +20 HMAC
        header = struct.pack("!HHI", msg_type, pre_len, MAGIC_COOKIE) + txn_id
        mac = hmac.new(key, header + attrs, hashlib.sha1).digest()
        attrs += _build_attr(ATTR_MESSAGE_INTEGRITY, mac)

    header = struct.pack("!HHI", msg_type, len(attrs), MAGIC_COOKIE) + txn_id
    return header + attrs


def _xor_address(ip: str, port: int, txn_id: bytes) -> bytes:
    """Encode XOR-MAPPED-ADDRESS / XOR-RELAYED-ADDRESS / XOR-PEER-ADDRESS."""
    xor_port = port ^ (MAGIC_COOKIE >> 16)
    ip_parts = [int(p) for p in ip.split(".")]
    ip_int = (ip_parts[0] << 24) | (ip_parts[1] << 16) | (ip_parts[2] << 8) | ip_parts[3]
    xor_ip = ip_int ^ MAGIC_COOKIE
    return struct.pack("!xBHI", 0x01, xor_port, xor_ip)  # family=IPv4


def _decode_xor_address(data: bytes, txn_id: bytes) -> Tuple[str, int]:
    """Decode XOR-PEER-ADDRESS."""
    family, xor_port, xor_ip = struct.unpack("!xBHI", data[:8])
    port = xor_port ^ (MAGIC_COOKIE >> 16)
    ip_int = xor_ip ^ MAGIC_COOKIE
    ip = f"{(ip_int >> 24) & 0xFF}.{(ip_int >> 16) & 0xFF}.{(ip_int >> 8) & 0xFF}.{ip_int & 0xFF}"
    return ip, port


# ── Allocation state ────────────────────────────────────────────────

class Allocation:
    """Represents a TURN allocation with its relay transport and state."""

    def __init__(
        self,
        client_addr: Tuple[str, int],
        relay_port: int,
        transport: asyncio.DatagramTransport,
        server: "TurnServerProtocol",
        hmac_key: Optional[bytes] = None,
        username: str = "",
    ):
        self.client_addr = client_addr
        self.relay_port = relay_port
        self.transport = transport
        self.server = server
        self.hmac_key = hmac_key
        self.username = username
        self.expires = time.time() + TURN_LIFETIME
        self.permissions: set = set()  # set of permitted peer IPs
        self.channels: Dict[int, Tuple[str, int]] = {}  # channel_number → (ip, port)
        self.reverse_channels: Dict[Tuple[str, int], int] = {}  # (ip, port) → channel_number


class RelayProtocol(asyncio.DatagramProtocol):
    """UDP protocol for a relay port — forwards data back to the TURN client."""

    def __init__(self, allocation: Allocation):
        self.allocation = allocation
        self.transport: Optional[asyncio.DatagramTransport] = None

    def connection_made(self, transport: asyncio.DatagramTransport) -> None:
        self.transport = transport

    def datagram_received(self, data: bytes, addr: Tuple[str, int]) -> None:
        alloc = self.allocation
        logger.info("Relay port %d received %d bytes from %s:%d", alloc.relay_port, len(data), addr[0], addr[1])

        # Check if there's a channel binding for this peer
        channel = alloc.reverse_channels.get(addr)
        if channel is not None:
            # Send as ChannelData
            channel_data = struct.pack("!HH", channel, len(data)) + data
            padding = (4 - len(data) % 4) % 4
            channel_data += b"\x00" * padding
            alloc.server.transport.sendto(channel_data, alloc.client_addr)
        else:
            # Send as Data Indication
            attrs = _build_attr(ATTR_XOR_PEER_ADDRESS, _xor_address(addr[0], addr[1], b"\x00" * 12))
            attrs += _build_attr(ATTR_DATA, data)
            msg = _build_msg(DATA_INDICATION, os.urandom(12), attrs, add_integrity=False)
            alloc.server.transport.sendto(msg, alloc.client_addr)


# ── Main TURN server protocol ──────────────────────────────────────

_NONCE_TTL = 300  # seconds


class TurnServerProtocol(asyncio.DatagramProtocol):
    def __init__(self, relay_ip: str):
        self.transport: Optional[asyncio.DatagramTransport] = None
        self.relay_ip = relay_ip
        self.allocations: Dict[Tuple[str, int], Allocation] = {}
        self._next_relay_port = RELAY_PORT_MIN
        self._nonces: Dict[str, float] = {}  # nonce → expiry timestamp

    def _new_nonce(self) -> str:
        """Generate a fresh nonce and track its expiry.

        Caps the nonce dictionary to prevent memory exhaustion from
        unauthenticated Allocate floods.
        """
        # Inline cleanup of expired nonces when approaching the cap
        if len(self._nonces) >= MAX_NONCES:
            now = time.time()
            stale = [n for n, exp in self._nonces.items() if now > exp]
            for n in stale:
                del self._nonces[n]
            if len(self._nonces) >= MAX_NONCES:
                # Still full after cleanup — refuse to issue new nonces
                logger.warning("Nonce table full (%d entries), rejecting", len(self._nonces))
                return ""
        nonce = os.urandom(16).hex()
        self._nonces[nonce] = time.time() + _NONCE_TTL
        return nonce

    def _validate_nonce(self, nonce: str) -> bool:
        """Check if a nonce is known and not expired."""
        expiry = self._nonces.get(nonce)
        if expiry is None or time.time() > expiry:
            self._nonces.pop(nonce, None)
            return False
        return True

    def connection_made(self, transport: asyncio.DatagramTransport) -> None:
        self.transport = transport
        logger.info("TURN server listening on port %d", TURN_PORT)
        self._cleanup_task = asyncio.ensure_future(self._expire_allocations())

    async def _expire_allocations(self) -> None:
        """Periodically remove expired TURN allocations and stale nonces."""
        while True:
            await asyncio.sleep(30)
            now = time.time()
            expired = [addr for addr, alloc in self.allocations.items() if alloc.expires <= now]
            for addr in expired:
                alloc = self.allocations.pop(addr)
                alloc.transport.close()
                logger.info("TURN allocation expired for %s:%d (relay :%d)", addr[0], addr[1], alloc.relay_port)
            # Purge expired nonces
            stale_nonces = [n for n, exp in self._nonces.items() if now > exp]
            for n in stale_nonces:
                del self._nonces[n]

    def connection_lost(self, exc) -> None:
        if hasattr(self, "_cleanup_task"):
            self._cleanup_task.cancel()
        # Clean up all relay transports
        for alloc_addr, alloc in list(self.allocations.items()):
            if alloc.transport:
                alloc.transport.close()
        self.allocations.clear()

    def datagram_received(self, data: bytes, addr: Tuple[str, int]) -> None:
        if len(data) < 4:
            return
        logger.debug("TURN main port received %d bytes from %s:%d", len(data), addr[0], addr[1])

        # Check if this is ChannelData (first two bits are 01)
        first_byte = data[0]
        if 0x40 <= first_byte <= 0x7F:
            self._handle_channel_data(data, addr)
            return

        parsed = _parse_header(data)
        if parsed is None:
            return

        msg_type, msg_len, txn_id = parsed
        attrs = _parse_attrs(data)

        try:
            if msg_type == BINDING_REQUEST:
                self._handle_binding(txn_id, addr)
            elif msg_type == ALLOCATE_REQUEST:
                self._handle_allocate(data, txn_id, attrs, addr)
            elif msg_type == REFRESH_REQUEST:
                self._handle_refresh(data, txn_id, attrs, addr)
            elif msg_type == CREATE_PERM_REQUEST:
                self._handle_create_permission(data, txn_id, attrs, addr)
            elif msg_type == CHANNEL_BIND_REQUEST:
                self._handle_channel_bind(data, txn_id, attrs, addr)
            elif msg_type == SEND_INDICATION:
                self._handle_send(attrs, addr)
        except Exception:
            logger.exception("Error handling STUN/TURN message type 0x%04x", msg_type)

    def _handle_binding(self, txn_id: bytes, addr: Tuple[str, int]) -> None:
        attrs = _build_attr(ATTR_XOR_MAPPED_ADDRESS, _xor_address(addr[0], addr[1], txn_id))
        msg = _build_msg(BINDING_RESPONSE, txn_id, attrs, add_integrity=False)
        self.transport.sendto(msg, addr)

    def _handle_allocate(self, data: bytes, txn_id: bytes, attrs: Dict[int, bytes], addr: Tuple[str, int]) -> None:
        # Check if already allocated
        if addr in self.allocations:
            alloc = self.allocations[addr]
            resp_attrs = _build_attr(ATTR_XOR_RELAYED_ADDRESS,
                                     _xor_address(self.relay_ip, alloc.relay_port, txn_id))
            resp_attrs += _build_attr(ATTR_XOR_MAPPED_ADDRESS, _xor_address(addr[0], addr[1], txn_id))
            resp_attrs += _build_attr(ATTR_LIFETIME, struct.pack("!I", TURN_LIFETIME))
            msg = _build_msg(ALLOCATE_RESPONSE, txn_id, resp_attrs, hmac_key=alloc.hmac_key)
            self.transport.sendto(msg, addr)
            return

        # Check for USERNAME (auth)
        if ATTR_USERNAME not in attrs:
            # Send 401 with REALM and fresh NONCE
            nonce = self._new_nonce()
            if not nonce:
                err = _build_attr(ATTR_ERROR_CODE, struct.pack("!xxBB", 5, 0) + b"Server Busy")
                msg = _build_msg(ALLOCATE_ERROR, txn_id, err, add_integrity=False)
                self.transport.sendto(msg, addr)
                return
            err = _build_attr(ATTR_ERROR_CODE, struct.pack("!xxBB", 4, 1) + b"Unauthorized")
            err += _build_attr(ATTR_REALM, REALM.encode())
            err += _build_attr(ATTR_NONCE, nonce.encode())
            msg = _build_msg(ALLOCATE_ERROR, txn_id, err, add_integrity=False)
            self.transport.sendto(msg, addr)
            return

        # Validate the nonce from the client's request
        client_nonce = attrs.get(ATTR_NONCE)
        if client_nonce is None or not self._validate_nonce(client_nonce.decode("utf-8", errors="replace")):
            err = _build_attr(ATTR_ERROR_CODE, struct.pack("!xxBB", 4, 38) + b"Stale Nonce")
            err += _build_attr(ATTR_REALM, REALM.encode())
            err += _build_attr(ATTR_NONCE, self._new_nonce().encode())
            msg = _build_msg(ALLOCATE_ERROR, txn_id, err, add_integrity=False)
            self.transport.sendto(msg, addr)
            return

        # Verify MESSAGE-INTEGRITY
        if not self._verify_message_integrity(data, attrs):
            err = _build_attr(ATTR_ERROR_CODE, struct.pack("!xxBB", 4, 1) + b"Bad credentials")
            err += _build_attr(ATTR_REALM, REALM.encode())
            err += _build_attr(ATTR_NONCE, self._new_nonce().encode())
            msg = _build_msg(ALLOCATE_ERROR, txn_id, err, add_integrity=False)
            self.transport.sendto(msg, addr)
            return

        # Derive the HMAC key used for this allocation's responses
        alloc_hmac_key = self._get_hmac_key(attrs)

        # Per-user allocation limit
        username = attrs[ATTR_USERNAME].decode("utf-8", errors="replace")
        user_alloc_count = sum(
            1 for a in self.allocations.values()
            if getattr(a, "username", None) == username
        )
        if user_alloc_count >= MAX_ALLOCATIONS_PER_USER:
            err = _build_attr(ATTR_ERROR_CODE, struct.pack("!xxBB", 4, 86) + b"Allocation Quota Reached")
            msg = _build_msg(ALLOCATE_ERROR, txn_id, err, add_integrity=False)
            self.transport.sendto(msg, addr)
            return

        # Allocate a relay port
        asyncio.ensure_future(self._do_allocate(txn_id, addr, alloc_hmac_key, username))

    async def _do_allocate(self, txn_id: bytes, addr: Tuple[str, int], alloc_hmac_key: Optional[bytes] = None, username: str = "") -> None:
        max_attempts = RELAY_PORT_MAX - RELAY_PORT_MIN + 1
        alloc = None

        for _ in range(max_attempts):
            relay_port = self._next_relay_port
            self._next_relay_port += 1
            if self._next_relay_port > RELAY_PORT_MAX:
                self._next_relay_port = RELAY_PORT_MIN

            alloc = Allocation(addr, relay_port, None, self, hmac_key=alloc_hmac_key, username=username)  # type: ignore

            try:
                loop = asyncio.get_running_loop()
                transport, _ = await loop.create_datagram_endpoint(
                    lambda: RelayProtocol(alloc),
                    local_addr=("0.0.0.0", relay_port),
                )
                alloc.transport = transport
                break
            except OSError:
                logger.debug("Relay port %d in use, trying next", relay_port)
                alloc = None
                continue

        if alloc is None or alloc.transport is None:
            logger.warning("No available relay ports in range %d-%d", RELAY_PORT_MIN, RELAY_PORT_MAX)
            err = _build_attr(ATTR_ERROR_CODE, struct.pack("!xxBB", 5, 0) + b"Server Error")
            msg = _build_msg(ALLOCATE_ERROR, txn_id, err, add_integrity=False)
            self.transport.sendto(msg, addr)
            return

        self.allocations[addr] = alloc
        logger.info("TURN allocation: %s:%d → relay :%d", addr[0], addr[1], relay_port)

        resp_attrs = _build_attr(ATTR_XOR_RELAYED_ADDRESS,
                                 _xor_address(self.relay_ip, relay_port, txn_id))
        resp_attrs += _build_attr(ATTR_XOR_MAPPED_ADDRESS, _xor_address(addr[0], addr[1], txn_id))
        resp_attrs += _build_attr(ATTR_LIFETIME, struct.pack("!I", TURN_LIFETIME))
        msg = _build_msg(ALLOCATE_RESPONSE, txn_id, resp_attrs, hmac_key=alloc_hmac_key)
        self.transport.sendto(msg, addr)

    def _handle_refresh(self, data: bytes, txn_id: bytes, attrs: Dict[int, bytes], addr: Tuple[str, int]) -> None:
        alloc = self.allocations.get(addr)
        if not alloc:
            return

        # Require MESSAGE-INTEGRITY for Refresh
        if not self._verify_message_integrity(data, attrs):
            return

        lifetime = TURN_LIFETIME
        if ATTR_LIFETIME in attrs:
            lifetime = struct.unpack("!I", attrs[ATTR_LIFETIME])[0]

        if lifetime == 0:
            # Delete allocation
            alloc.transport.close()
            del self.allocations[addr]
            logger.info("TURN allocation deleted for %s:%d", addr[0], addr[1])
        else:
            alloc.expires = time.time() + lifetime

        resp_attrs = _build_attr(ATTR_LIFETIME, struct.pack("!I", lifetime))
        msg = _build_msg(REFRESH_RESPONSE, txn_id, resp_attrs, hmac_key=alloc.hmac_key)
        self.transport.sendto(msg, addr)

    def _handle_create_permission(self, data: bytes, txn_id: bytes, attrs: Dict[int, bytes], addr: Tuple[str, int]) -> None:
        alloc = self.allocations.get(addr)
        if not alloc:
            return

        # Require MESSAGE-INTEGRITY for CreatePermission
        if not self._verify_message_integrity(data, attrs):
            return

        if ATTR_XOR_PEER_ADDRESS in attrs:
            peer_ip, _ = _decode_xor_address(attrs[ATTR_XOR_PEER_ADDRESS], txn_id)
            alloc.permissions.add(peer_ip)
            logger.debug("TURN permission: %s allowed for %s:%d", peer_ip, addr[0], addr[1])

        msg = _build_msg(CREATE_PERM_RESPONSE, txn_id, b"", hmac_key=alloc.hmac_key)
        self.transport.sendto(msg, addr)

    def _handle_channel_bind(self, data: bytes, txn_id: bytes, attrs: Dict[int, bytes], addr: Tuple[str, int]) -> None:
        alloc = self.allocations.get(addr)
        if not alloc:
            return

        # Require MESSAGE-INTEGRITY for ChannelBind
        if not self._verify_message_integrity(data, attrs):
            return

        if ATTR_CHANNEL_NUMBER not in attrs or ATTR_XOR_PEER_ADDRESS not in attrs:
            return

        channel = struct.unpack("!HH", attrs[ATTR_CHANNEL_NUMBER])[0]
        peer_ip, peer_port = _decode_xor_address(attrs[ATTR_XOR_PEER_ADDRESS], txn_id)

        alloc.channels[channel] = (peer_ip, peer_port)
        alloc.reverse_channels[(peer_ip, peer_port)] = channel
        alloc.permissions.add(peer_ip)
        logger.debug("TURN channel %d → %s:%d for %s:%d", channel, peer_ip, peer_port, addr[0], addr[1])

        msg = _build_msg(CHANNEL_BIND_RESPONSE, txn_id, b"", hmac_key=alloc.hmac_key)
        self.transport.sendto(msg, addr)

    def _handle_send(self, attrs: Dict[int, bytes], addr: Tuple[str, int]) -> None:
        alloc = self.allocations.get(addr)
        if not alloc:
            logger.warning("Send indication from %s:%d but no allocation", addr[0], addr[1])
            return

        if ATTR_XOR_PEER_ADDRESS not in attrs or ATTR_DATA not in attrs:
            return

        peer_ip, peer_port = _decode_xor_address(attrs[ATTR_XOR_PEER_ADDRESS], b"\x00" * 12)

        # RFC 5766 §10.2: drop if peer IP has no installed permission
        if peer_ip not in alloc.permissions:
            logger.debug("Send indication dropped: no permission for %s", peer_ip)
            return

        logger.info("Send indication: %s:%d → %s:%d (%d bytes)", addr[0], addr[1], peer_ip, peer_port, len(attrs[ATTR_DATA]))
        alloc.transport.sendto(attrs[ATTR_DATA], (peer_ip, peer_port))

    def _get_hmac_key(self, attrs: Dict[int, bytes]) -> Optional[bytes]:
        """Derive the HMAC key for MESSAGE-INTEGRITY verification.

        Only supports ephemeral credentials (TURN_SECRET).
        """
        if ATTR_USERNAME not in attrs:
            return None

        username = attrs[ATTR_USERNAME].decode("utf-8", errors="replace")

        if not TURN_SECRET or ":" not in username:
            return None

        # Ephemeral mode: username = "<expiry>:<user_id>"
        try:
            expiry_str = username.split(":")[0]
            expiry = int(expiry_str)
        except ValueError:
            return None
        if time.time() > expiry:
            logger.debug("Ephemeral TURN credential expired for %s", username)
            return None
        password = _ephemeral_password(username)
        return _long_term_key(username, REALM, password)

    def _verify_message_integrity(self, data: bytes, attrs: Dict[int, bytes]) -> bool:
        """Verify the MESSAGE-INTEGRITY attribute."""
        if ATTR_MESSAGE_INTEGRITY not in attrs:
            return False

        hmac_key = self._get_hmac_key(attrs)
        if hmac_key is None:
            return False

        received_mac = attrs[ATTR_MESSAGE_INTEGRITY]
        # Find where MESSAGE-INTEGRITY attribute starts in the raw data
        offset = HEADER_SIZE
        while offset + 4 <= len(data):
            attr_type, attr_len = struct.unpack_from("!HH", data, offset)
            if attr_type == ATTR_MESSAGE_INTEGRITY:
                break
            offset += 4 + attr_len + (4 - attr_len % 4) % 4
        else:
            return False
        # Rewrite the message length in the header to cover up to MESSAGE-INTEGRITY
        mi_len = offset - HEADER_SIZE + 4 + 20  # attr header (4) + HMAC (20)
        modified = bytearray(data[:offset])
        struct.pack_into("!H", modified, 2, mi_len)
        expected_mac = hmac.new(hmac_key, bytes(modified), hashlib.sha1).digest()
        return hmac.compare_digest(expected_mac, received_mac)

    def _handle_channel_data(self, data: bytes, addr: Tuple[str, int]) -> None:
        alloc = self.allocations.get(addr)
        if not alloc:
            return

        if len(data) < 4:
            return

        channel, length = struct.unpack("!HH", data[:4])
        payload = data[4:4 + length]

        peer = alloc.channels.get(channel)
        if peer:
            alloc.transport.sendto(payload, peer)


async def start_turn_server(relay_ip: str = "0.0.0.0") -> asyncio.DatagramTransport:
    """Start the TURN server on UDP port 3478.

    Args:
        relay_ip: The IP address to advertise for relay allocations.
                  For Android emulators, this should be the host IP
                  that the emulators can reach (typically the LAN IP).
    """
    loop = asyncio.get_running_loop()
    transport, _ = await loop.create_datagram_endpoint(
        lambda: TurnServerProtocol(relay_ip),
        local_addr=("0.0.0.0", TURN_PORT),
    )
    logger.info("TURN server started on 0.0.0.0:%d, relay IP: %s", TURN_PORT, relay_ip)
    return transport
