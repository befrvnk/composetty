# Releasing Composetty

Composetty releases are built from an annotated Git tag on macOS, after all four desktop native
libraries have been built on their supported hosts. The first Central deployment is intentionally
left unpublished so it can be inspected in the Central Portal.

## One-time setup

1. Verify the Maven Central namespace `dev.befrvnk.composetty` in the Central Portal.
2. Generate a Central Portal user token. Its username and password are publishing credentials, not
   the normal portal login.
3. Create a password-protected OpenPGP signing key and distribute its public key through an accepted
   keyserver.
4. Store the following fields in 1Password:
   - Maven Central token username
   - Maven Central token password
   - ASCII-armored private OpenPGP key
   - private-key passphrase
5. Create a read-only 1Password service account with access to the vault containing the publishing
   items. Service-account permissions are vault-scoped, so a dedicated publishing vault is
   recommended instead of granting CI access to an unrelated general-purpose vault.
6. Create a protected GitHub environment named `maven-central` and require manual approval.
7. Add the service account token as the `OP_SERVICE_ACCOUNT_TOKEN` environment secret.
8. Keep the checked-in `op://` references in `.github/workflows/publish.yml` aligned with the
   publishing item if its vault, item, or field names change.

The workflow uses 1Password's service-account action to resolve those references only inside the
protected publishing job. The ASCII-armored private key is used directly in memory, so neither a
`secring.gpg` file nor a key ID is required. `signingInMemoryKeyId` is optional and is only useful
when explicitly selecting among multiple signing identities.

## Local credential preflight

The canonical release engine consists of two Gradle tasks:

- `releasePreflight` checks, signs, and consumes the release without contacting Maven Central.
- `releaseUpload` runs the same preflight and uploads a user-managed deployment.

The credential and signing setup can be checked without uploading:

```shell
scripts/release-local-check.sh 0.1.0-alpha01
```

The script is only a 1Password convenience wrapper around `releasePreflight`. It resolves the same
checked-in secret references, signs every publication, and publishes only to the isolated
`build/consumer-repository`. Do not print the environment or run Gradle with debug logging while
credentials are loaded.

A real Central upload can also be performed locally after all four desktop native resources have
been collected into one directory:

```shell
op run --env-file=scripts/release.env -- \
  devenv shell -- ./gradlew \
    -PVERSION_NAME=0.1.0-alpha01 \
    -Pcomposetty.nativeResources="$PWD/release-native-resources" \
    -PconfirmMavenCentralUpload=0.1.0-alpha01 \
    releaseUpload \
    --no-daemon
```

The explicit confirmation must exactly match the release version. `releaseUpload` uses
`publishToMavenCentral`, so the resulting deployment is validated but remains unpublished. It can
be inspected, downloaded for testing, published, or dropped in the Central Portal. A local upload
is the definitive check for the new Portal token; the offline preflight intentionally does not use
Central credentials over the network.

## Prepare a release

1. Confirm `main` is green on all four desktop hosts and the Android x86-64 emulator job.
2. Decide the version. Supported release forms are `X.Y.Z`, `X.Y.Z-alphaN`, `X.Y.Z-betaN`, and
   `X.Y.Z-rcN`.
3. Move the relevant notes from `Unreleased` to a dated `## [VERSION]` section in `CHANGELOG.md`.
4. Review public documentation:
    - Confirm the [compatibility matrix](usage.md#compatibility) matches the published targets.
    - Confirm [current limitations](usage.md#current-limitations) and sample links remain accurate.
    - Confirm the [remote transport sample](../samples/remote) still builds and its integration
      guidance matches the public API.
    - Confirm the hosted [API reference](https://befrvnk.github.io/composetty/) deploys from `main`.
5. Run:

   ```shell
   devenv build outputs.native
   devenv build outputs.androidNative
   devenv build outputs.iosNative
   devenv shell -- ./gradlew clean check consumerSmokeTest --no-daemon
   samples/ios/check.sh
   devenv shell -- ./gradlew verifyReleaseVersion -PVERSION_NAME=VERSION --no-daemon
   ```

6. Commit the changelog and release preparation.
7. Create and push an annotated tag:

   ```shell
   git tag -s vVERSION -m "Composetty VERSION"
   git push origin vVERSION
   ```

Pushing the signed `vVERSION` tag starts the `Publish release candidate` workflow. The workflow
checks out that exact tag and rejects malformed tags, versions that do not match the tag, and
versions without a changelog heading. It pauses for approval of the protected `maven-central`
environment before credentials are loaded or a deployment is uploaded.

## Inspect and publish

After the protected GitHub environment is approved, the workflow:

1. Tests and exports each desktop native bridge.
2. Aggregates desktop, Android, and iOS native artifacts on macOS.
3. Loads Central and signing credentials from 1Password.
4. Runs all checks, standalone published-consumer tests, POM checks, and signing checks.
5. Runs the same `releaseUpload` Gradle task used locally.

The deployment remains unpublished. In the Central Portal:

1. Confirm validation succeeded.
2. Inspect every publication and signature.
3. Confirm JVM resources, Android AAR libraries, iOS KLIBs, source jars, generated Dokka API
   documentation JARs, POMs, checksums, licenses, and notices are present.
4. Publish the deployment manually.
5. Wait for the artifacts to become available from Maven Central.
6. Re-run the standalone consumer against the Central repository if practical.
7. Create a GitHub release from the existing tag using the matching changelog section.

If validation or inspection fails, drop the deployment in the portal. Do not reuse a released
version number.
