"""Minimal Ethereum JSON-RPC helpers using only the stdlib (urllib).

No web3.py / eth-account dependency. Provides:
  - RPC: JSON-RPC over HTTP (eth_call, chainId, nonce, gas, broadcast)
  - Keccak-256 (pure python)
  - secp256k1 signing (pure python) for EIP-155 legacy transactions
  - RLP encoding for tx serialization

read_endpoint.py needs only RPC + keccak. deploy.py / update_endpoint.py
additionally use the signing path.
"""

import json
import urllib.request

# --- Keccak-256 (pure python) ------------------------------------------------

_KECCAK_RC = [
    0x0000000000000001, 0x0000000000008082, 0x800000000000808A,
    0x8000000080008000, 0x000000000000808B, 0x0000000080000001,
    0x8000000080008081, 0x8000000000008009, 0x000000000000008A,
    0x0000000000000088, 0x0000000080008009, 0x000000008000000A,
    0x000000008000808B, 0x800000000000008B, 0x8000000000008089,
    0x8000000000008003, 0x8000000000008002, 0x8000000000000080,
    0x000000000000800A, 0x800000008000000A, 0x8000000080008081,
    0x8000000000008080, 0x0000000080000001, 0x8000000080008008,
]
_M64 = 0xFFFFFFFFFFFFFFFF

# rho offsets for lanes indexed by (x, y); pi moves (x,y) <- (y, (2x+3y)%5)
_RHO = [
    [0, 36, 3, 41, 18],
    [1, 44, 10, 45, 2],
    [62, 6, 43, 15, 61],
    [28, 55, 25, 21, 56],
    [27, 20, 39, 8, 14],
]


def _rotl(x, n):
    return ((x << n) | (x >> (64 - n))) & _M64


def keccak256(data: bytes) -> bytes:
    rate = 136  # bytes, keccak-256
    state = [[0] * 5 for _ in range(5)]
    # pad10*1
    pad = data + b"\x01"
    pad += b"\x00" * ((-len(pad)) % rate)
    pad = pad[:-1] + bytes([pad[-1] | 0x80])
    for off in range(0, len(pad), rate):
        blk = pad[off:off + rate]
        for i in range(rate // 8):
            w = int.from_bytes(blk[i * 8:(i + 1) * 8], "little")
            state[i % 5][i // 5] ^= w
        for rnd in range(24):
            # theta
            c = [state[x][0] ^ state[x][1] ^ state[x][2] ^ state[x][3] ^ state[x][4]
                 for x in range(5)]
            d = [c[(x - 1) % 5] ^ _rotl(c[(x + 1) % 5], 1) for x in range(5)]
            for x in range(5):
                for y in range(5):
                    state[x][y] ^= d[x]
            # rho + pi
            B = [[0] * 5 for _ in range(5)]
            for x in range(5):
                for y in range(5):
                    B[y][(2 * x + 3 * y) % 5] = _rotl(state[x][y], _RHO[x][y])
            # chi
            for x in range(5):
                for y in range(5):
                    state[x][y] = B[x][y] ^ ((~B[(x + 1) % 5][y] & _M64)
                                            & B[(x + 2) % 5][y])
            # iota
            state[0][0] ^= _KECCAK_RC[rnd]
    out = b""
    for y in range(5):
        for x in range(5):
            out += state[x][y].to_bytes(8, "little")
    return out[:32]


# --- secp256k1 ---------------------------------------------------------------

P = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def _inv(a, m):
    return pow(a, m - 2, m)


def _add(p, q):
    if p is None:
        return q
    if q is None:
        return p
    if p[0] == q[0]:
        if (p[1] + q[1]) % P == 0:
            return None
        lam = (3 * p[0] * p[0]) * _inv(2 * p[1], P) % P
    else:
        lam = (q[1] - p[1]) * _inv(q[0] - p[0], P) % P
    x = (lam * lam - p[0] - q[0]) % P
    return x, (lam * (p[0] - x) - p[1]) % P


def _mul(k, point=G):
    r = None
    while k:
        if k & 1:
            r = _add(r, point)
        point = _add(point, point)
        k >>= 1
    return r


def privkey_to_address(priv: bytes) -> str:
    assert len(priv) == 32
    pub = _mul(int.from_bytes(priv, "big"))
    h = keccak256(pub[0].to_bytes(32, "big") + pub[1].to_bytes(32, "big"))
    return "0x" + h[-20:].hex()


def sign(priv: bytes, digest: bytes):
    """Return (r, s, recovery_id) for a 32-byte digest."""
    z = int.from_bytes(digest, "big")
    k = int.from_bytes(keccak256(digest + priv), "big") % N or 1
    while True:
        p = _mul(k)
        r = p[0] % N
        if r:
            s = _inv(k, N) * (z + r * int.from_bytes(priv, "big")) % N
            if s:
                if s > N // 2:  # low-s malleability fix
                    s = N - s
                    v = (p[1] % 2) ^ 1
                else:
                    v = p[1] % 2
                if z > N:
                    z = z - N  # rare; still fits secp256k1 convention
                return r, s, v
        k = (k + 1) % N or 1


# --- RLP ---------------------------------------------------------------------


def rlp_encode(item) -> bytes:
    if isinstance(item, int):
        if item == 0:
            return b"\x80"
        b = item.to_bytes((item.bit_length() + 7) // 8, "big")
        return rlp_encode(b)
    if isinstance(item, bytes):
        if len(item) == 1 and item[0] < 0x80:
            return item
        return _rlp_len(len(item), 0x80) + item
    payload = b"".join(rlp_encode(x) for x in item)
    return _rlp_len(len(payload), 0xC0) + payload


def _rlp_len(length, offset):
    if length < 56:
        return bytes([offset + length])
    b = length.to_bytes((length.bit_length() + 7) // 8, "big")
    return bytes([offset + 55 + len(b)]) + b


# --- JSON-RPC over HTTP --------------------------------------------------------


class RPC:
    def __init__(self, url, timeout=20):
        self.url = url
        self.timeout = timeout
        self._id = 0

    def call(self, method, params=None):
        self._id += 1
        payload = json.dumps({
            "jsonrpc": "2.0", "id": self._id, "method": method,
            "params": params or [],
        }).encode()
        req = urllib.request.Request(
            self.url, data=payload,
            headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=self.timeout) as r:
            resp = json.loads(r.read())
        if "error" in resp:
            raise RuntimeError(f"RPC error from {self.url}: {resp['error']}")
        return resp["result"]


def send_tx(rpc: RPC, priv: bytes, to, data: bytes, chain_id: int,
            gas: int = None, value: int = 0):
    """Build, sign (EIP-155) and broadcast a legacy tx. Returns tx hash."""
    addr = privkey_to_address(priv)
    if to is not None:
        to_b = bytes.fromhex(to[2:] if to.startswith("0x") else to)
    else:
        to_b = b""
    nonce = int(rpc.call("eth_getTransactionCount", [addr, "pending"]), 16)
    if gas is None:
        gas = int(rpc.call("eth_estimateGas", [{
            "from": addr, "to": to, "data": "0x" + data.hex(),
            "value": hex(value)}]), 16)
        gas = min(int(gas * 1.3) + 1000, 30_000_000)
    price = int(rpc.call("eth_gasPrice", []), 16)
    unsigned = [nonce, price, gas, to_b, value, data, chain_id, 0, 0]
    h = keccak256(rlp_encode(unsigned))
    r, s, v = sign(priv, h)
    signed = [nonce, price, gas, to_b, value, data, v + 35 + 2 * chain_id, r, s]
    raw = "0x" + rlp_encode(signed).hex()
    return rpc.call("eth_sendRawTransaction", [raw])


def wait_receipt(rpc: RPC, tx_hash: str, timeout_s: int = 60):
    import time
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        rc = rpc.call("eth_getTransactionReceipt", [tx_hash])
        if rc is not None:
            if rc.get("status") != "0x1":
                raise RuntimeError(f"tx {tx_hash} reverted: {rc}")
            return rc
        time.sleep(1)
    raise TimeoutError(f"no receipt for {tx_hash} in {timeout_s}s")
