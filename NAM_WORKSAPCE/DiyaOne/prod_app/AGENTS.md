# DiyaOne Android maintenance

The user accepted debug v2.3.2 (version code 602008) on 2026-11-20. On the original workstation, the source snapshot, APK, and SHA-256 checksums are in `outputs/baselines/diyaone-v2.3.2-tested-2026-11-20/`.

Use the accepted baseline as a reference when changing existing behavior. Keep changes focused on the user's request and verify the flows the change touches. For chat changes, check attachment previews and delivery status after reopening a conversation, local message deletion after navigating away and back, and scroll position after sending. For account changes, check that each number sees its own history and that same-number sign-in recovers saved chat and call history. For call audio changes, include outgoing ringback on the Vivo Y29 when the handset is available.

Store new APKs separately from the accepted baseline. Capture a new baseline when the user accepts a subsequent build.
