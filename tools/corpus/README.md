# Capture corpus manifests

Images and frame recordings never enter git. This directory holds manifests only:
one TSV per collection with `id, issuing_state, device_tier, device_model, lighting,
condition, line1, line2, x0..y3` and a pointer to the storage location.

Every false accept and every unverified session from the device lab is added to the
validation manifest with its frames before it is ever considered for training.
