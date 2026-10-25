# Suggested free public RPC endpoints per chain (no API keys, subject to
# rate limits — the client should try them in order with fallback).
#
# These lists are candidates; verify they still work / fit current limits
# at deploy time (chainlist.org is a good source of fresh public RPCs).
#
# Polygon PoS (chainId 137)
#   https://polygon-rpc.com
#   https://rpc.ankr.com/polygon
#   https://polygon-bor-rpc.publicnode.com
#   https://1rpc.io/matic
#
# Base (chainId 8453)
#   https://mainnet.base.org
#   https://base-rpc.publicnode.com
#   https://1rpc.io/base
#   https://base.llamarpc.org
#
# Optimism (chainId 10)
#   https://mainnet.optimism.io
#   https://optimism-rpc.publicnode.com
#   https://1rpc.io/op

RPCS = {
    137: [
        "https://polygon-rpc.com",
        "https://rpc.ankr.com/polygon",
        "https://polygon-bor-rpc.publicnode.com",
        "https://1rpc.io/matic",
    ],
    8453: [
        "https://mainnet.base.org",
        "https://base-rpc.publicnode.com",
        "https://1rpc.io/base",
        "https://base.llamarpc.org",
    ],
    10: [
        "https://mainnet.optimism.io",
        "https://optimism-rpc.publicnode.com",
        "https://1rpc.io/op",
    ],
}

# anvil local dev chain
RPCS[31337] = ["http://127.0.0.1:8545"]
