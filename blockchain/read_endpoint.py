#!/usr/bin/env python3
"""Read the current C2 endpoint from a deployed EndpointRegistry contract.

Plain JSON-RPC eth_call via urllib — no web3.py needed.

Usage:
    python3 read_endpoint.py --rpc http://127.0.0.1:8545 \
        --address 0x<contract address>

The script tries each RPC if --rpc is given multiple times (fallback list).
Prints just the endpoint string (suitable for scripting).
"""

import argparse
import sys

from ethlib import RPC, keccak256

# selector for endpoint()
SELECTOR = keccak256(b"endpoint()")[:4].hex()


def decode_string(result: str) -> str:
    data = bytes.fromhex(result[2:] if result.startswith("0x") else result)
    offset = int.from_bytes(data[0:32], "big")
    length = int.from_bytes(data[offset:offset + 32], "big")
    return data[offset + 32:offset + 32 + length].decode("utf-8")


def read(rpc_url: str, address: str) -> str:
    rpc = RPC(rpc_url)
    res = rpc.call("eth_call", [{
        "to": address,
        "data": "0x" + SELECTOR,
    }, "latest"])
    if res in ("0x", "0x0"):
        raise RuntimeError("empty result — wrong address or not deployed?")
    return decode_string(res)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--rpc", action="append", required=True,
                    help="RPC URL (repeat for fallback list)")
    ap.add_argument("--address", required=True, help="contract address")
    args = ap.parse_args()

    last_err = None
    for url in args.rpc:
        try:
            print(read(url, args.address))
            return
        except Exception as e:
            last_err = e
            print(f"warning: {url} failed: {e}", file=sys.stderr)
    sys.exit(f"error: all RPCs failed: {last_err}")


if __name__ == "__main__":
    main()
