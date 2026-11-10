package com.foxhole.vpn.engine

/** Android supplies its SDK; profile translation supplies the effective protocol set. */
fun validateVpnRoutingPlatform(sdkInt: Int, protocols: Set<String>, routing: VpnRoutingPolicy) {
    if (sdkInt < 30 && "wireguard" in protocols && routing.webRules.any {
            !it.mask.startsWith("cidr:") && !it.mask.startsWith("range:")
        }) throw EngineException(EngineFailure.WIREGUARD_DOMAIN_PLATFORM)
}
