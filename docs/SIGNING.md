# Signing

## MVP (this release)

- **Installable debug APK** published as GitHub Release asset.
- Marked as test build (`applicationId` suffix `.debug`, version suffix `-debug`).
- **No production signing secrets** configured.

## Production (optional later)

Store in GitHub Actions secrets (never commit):

- `SIGNING_KEYSTORE_BASE64`
- `SIGNING_KEY_ALIAS`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_PASSWORD`

Release workflow signs only when secrets are present. Unsigned release artifacts must be labeled non-installable build products.
