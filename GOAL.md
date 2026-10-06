# Summary
Integrate the existing ZeroTierOne engine into Rethink for Android. Users can add and remove networks, see the node’s online/offline state and host ID, and route traffic through ZeroTier using Rethink’s existing VPN service. Route ZeroTier-advertised prefixes, including a default route when the network advertises one.

# Implementation
- Build ZeroTierOne’s existing Android JNI SDK from the separate ZeroTier source tree for Rethink’s supported Android ABIs. Package the compiled libraries and SDK interface with Rethink; keep ZeroTier source out of the Rethink repository and make no ZeroTier source changes.
- Add a Rethink-owned adapter and lifecycle manager for persistent node identity, network membership, status updates, and SDK callbacks. Keep node-online status distinct from each network’s authorization/configuration status.
- Add a minimal screen to enter a network ID, join or leave, and view joined networks, status, and host ID.
- Bridge Rethink’s existing VPN data path to the ZeroTier SDK: translate packets and frames, apply network configuration and routes, and send ZeroTier wire packets over the device’s available physical network. Do not introduce a second Android VPN service.
- Keep the integration internal to Rethink; no new public API or external wire protocol.
- Ensure `zerotier-cli` can be run from within Termux and can talk to the "ZeroTierOne" service (within or alongside Rethink) and looks in Termux paths when `/var` does not exist so that zerotier-cli info works without requiring a port, ip, and token every time (i.e. allow it to cache the token and default to 127.0.0.1:9993)
- Ensure the build can easily be repeated in future by providing an easy-to-use build script (no arguments should be required unless overrides are required), and the use of it is documented.

# Verification
- Cover network ID input, persistent identity, join/leave, status transitions, route configuration, and packet/frame conversion with focused tests.
- Build the Android app and packaged ZeroTier JNI libraries for the supported ABIs.
- On Flip7, verify host ID stability across restart, joining and leaving a test network, online/offline reporting through a connectivity interruption and recovery, and traffic to ZeroTier-managed routes. Verify a default route only when the test network advertises one.
- Ping to flip7 should be comparable to the ping times using the native ZeroTier Android app, comparable TTL (64) and no DUP packets.

# Assumptions
- “Online” means the ZeroTier node reports connectivity; network authorization/configuration is shown separately.
- Rethink remains the sole owner of Android’s VPN service. ZeroTier traffic follows routes advertised by joined networks, including an advertised default route.
- Before implementation, confirm how the existing ZeroTier SDK’s GPL-3.0 terms and Android build requirements apply to Rethink’s distribution.
