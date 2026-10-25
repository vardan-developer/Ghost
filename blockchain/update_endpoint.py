#!/usr/bin/env python3
"""Update the C2 endpoint on a deployed EndpointRegistry (owner-only tx).

Private key comes from env var GHOST_DEPLOY_KEY (must be the deployer /
owner). NEVER hardcode keys.

Usage:
    export GHOST_DEPLOY_KEY=0x...
    python3 update_endpoint.py --rpc http://127.0.0.1:8545 \
        --address 0x<contract> --endpoint newhost.example.com:443
"""

import argparse
import os
import sys

from ethlib import RPC, keccak256, send_tx, wait_receipt

# setEndpoint(string)
SELECTOR = keccak256(b"setEndpoint(string)")[:4]


def encode_string(s: str) -> bytes:
    b = s.encode("utf-8")
    return (32).to_bytes(32, "big") + len(b).to_bytes(32, "big") + \
        b + b"\x00" * ((-len(b)) % 32)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rpc", required=True, help="JSON-RPC URL")
    ap.add_argument("--address", required=True, help="contract address")
    ap.add_argument("--endpoint", required=True,
                    help="new C2 endpoint (host[:port])")
    args = ap.parse_args()

    k = os.environ.get("GHOST_DEPLOY_KEY")
    if not k:
        sys.exit("error: set GHOST_DEPLOY_KEY env var (owner's hex private key)")
    priv = bytes.fromhex(k[2:] if k.startswith("0x") else k)

    rpc = RPC(args.rpc)
    chain_id = int(rpc.call("eth_chainId", []), 16)
    data = bytes.fromhex(SELECTOR.hex()) + encode_string(args.endpoint)
    tx_hash = send_tx(rpc, priv, args.address, data, chain_id)
    rc = wait_receipt(rpc, tx_hash)
    print(f"ok: setEndpoint -> {args.endpoint}")
    print(f"tx: {tx_hash} (block {int(rc['blockNumber'], 16)})")


if __name__ == "__main__":
    main()
