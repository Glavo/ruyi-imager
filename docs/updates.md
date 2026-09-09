# Application updates

The updater reads a static JSON manifest, chooses a compatible installer, verifies
it, and hands it to the operating system. A static HTTPS host and package storage
are sufficient; no update API server is required. Repository metadata updates are
a separate feature.

## Sources and commands

The default source is `update-manifest.json` in the application configuration
directory. A missing default file silently disables startup checks. No public
update endpoint is configured yet.

Set the JVM property `ruyi.imager.update.source` to a local path, file URI, or HTTPS
URL to override the source for both GUI and CLI. For example, launch with
`JAVA_TOOL_OPTIONS=-Druyi.imager.update.source=https://updates.example.org/manifest.json`.
The example host is a placeholder, not a deployed service.

The CLI also accepts an explicit source:

```sh
ruyi-imager-cli check-update --source ./update-manifest.local.json --channel stable
ruyi-imager-cli check-update --source https://updates.example.org/manifest.json --prepare
ruyi-imager-cli check-update --source https://updates.example.org/manifest.json --install
```

`--prepare` downloads or copies and verifies the selected installer. `--install`
also starts it. Checks alone never install anything. A newer release without a
compatible installer is reported separately; requesting preparation or installation
in that case exits with status 1.

The GUI checks at startup when the selected channel's last successful check is at
least one hour old or unknown, then attempts another check every four hours while
open. Each timer tick rereads the automatic-check setting and selected channel.
Ticks during background work or an owned dialog are skipped until the next cycle;
failures stay silent and do not trigger immediate retries. Closing the window stops
the timer. Manual checks bypass the interval and automatic-check setting.
The stable/nightly preference and exact-release skip state are retained. Installation
requires user confirmation; after starting the installer, the GUI exits. Handoff
does not prove that the installation finished successfully.

## Manifest

The schema remains version 1. This example is illustrative: replace the package
size, digest, and location with values from a real release before publishing it.

```json
{
  "schemaVersion": 1,
  "releases": [
    {
      "channel": "stable",
      "version": "1.1.0",
      "releaseNotes": "Release notes shown before installation",
      "requirements": {
        "minimumAppVersion": "1.0.0"
      },
      "artifacts": [
        {
          "platform": "windows-x86_64",
          "packageType": "setup-exe",
          "source": "packages/ruyi-imager-1.1.0-windows-x86_64-setup.exe",
          "size": 123456789,
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "requirements": {
            "minimumSystemVersion": "10.0"
          }
        }
      ]
    }
  ]
}
```

- `releases` may be empty. Channels are `stable` and `nightly`; channel membership
  is independent of the version's prerelease label.
- `version` is the sole ordering key, using the application's Burn-compatible
  comparator. Build metadata does not change precedence. Equal-precedence releases
  in the same channel are rejected as ambiguous.
- `releaseNotes` is optional plain text. HTML, executable commands, and installer
  arguments from the manifest are not interpreted.
- `artifacts` may be empty. Each `(platform, packageType)` pair must be unique
  within a release. Different package types for one platform are allowed.
- `size` is a positive integer byte count. `sha256` is exactly 64 hexadecimal
  digits, case-insensitive. Both must match the completed file.
- For a local manifest, relative `source` values are filesystem paths confined to
  its real directory, including after symbolic-link resolution. Absolute HTTPS
  URLs are also allowed.
- For an HTTPS manifest, `source` is an HTTPS URL or URI reference resolved against
  the configured manifest URL, not its final redirect destination. Remote feeds
  cannot access local files. Absolute package URLs are preferable when moving feeds
  between hosts. HTTPS URLs cannot contain credentials or fragments.

Supported platform IDs are `windows-x86_64`, `windows-aarch64`, `linux-x86_64`,
`linux-aarch64`, `linux-riscv64`, `macos-x86_64`, and `macos-aarch64`.

Supported installers are Windows `setup-exe`, Linux `deb`, and macOS `pkg` or `dmg`.
Linux requires a detected `dpkg`; macOS prefers `pkg` over `dmg`. These capabilities
describe installer handoff, not which packages the release workflow currently
publishes. Portable ZIP and TAR archives are not installed by the updater.

An OS or architecture without update support does not prevent GUI startup.
Version checks remain available, but newer releases are informational only and
installer preparation is disabled.

## Compatibility and extension rules

Optional `requirements` objects can appear on a release, an artifact, or both.
Every condition at both levels must pass:

- `minimumAppVersion`: the minimum installed application version, compared with
  the same comparator used for update ordering.
- `minimumSystemVersion`: one to four decimal components, each at most ten digits.
  Windows uses the native major.minor.build version from
  [RtlGetVersion](https://learn.microsoft.com/en-us/windows/win32/devnotes/rtlgetversion);
  other platforms use Java's `os.version`. Missing components count as zero and
  an OS suffix such as a Linux kernel label is ignored. On Linux this is the kernel
  version, not a distribution or glibc version. Unavailable or unparseable version
  information cannot satisfy this condition. Windows cumulative-update revision
  numbers are not detected.

The client first filters compatible installers, then chooses the newest eligible
release newer than the installed version. A newer incompatible release does not
hide an older compatible update. If all newer releases are incompatible, the
newest is available for informational display only.

Unknown descriptive fields are ignored. Unknown channels, platform IDs, and
package types are skipped. Unknown keys inside `requirements` make that release
or artifact ineligible. Add future mandatory installation conditions there, not
as ordinary descriptive fields. `requirements` is not a root-level field.

Known fields remain strict: invalid types or values, duplicate JSON keys, trailing
JSON documents, and unsupported schema versions reject the feed. New incompatible
protocol semantics require an explicit schema change; adding optional metadata
does not.

## Transfer and installation safety

- Manifests are capped at 1 MiB. HTTPS metadata reads have a 60-second deadline;
  HTTPS package transfers have a 30-minute total deadline. Network waits are
  interruptible. Synchronous progress
  callbacks and filesystem operations cannot be forcibly preempted by the deadline.
- HTTPS uses the normal TLS trust configuration and application proxy defaults.
  At most five redirects are followed, each subject to the HTTPS policy. Non-200
  final responses and non-identity content encodings are rejected.
- Package size limits are checked before writes, including chunked responses.
  Transfer or verification failure triggers temporary-file cleanup; cleanup errors
  are retained with the original failure. Only verified packages enter the final
  content-addressed cache. Existing cache entries are reverified before reuse and
  do not require the source to remain available.
- Installer handoff rechecks compatibility, size, and SHA-256. Commands and file
  extensions are selected by application code, never by manifest-provided commands.
  Windows starts the setup executable; Linux and macOS ask the OS to open the package.
- There is no manifest or package signature mechanism. SHA-256 checks downloaded
  bytes against the manifest; HTTPS and control of the configured source establish
  trust in that manifest. A compromised source can replace both package and digest.
  The protocol does not prevent a source from withholding an available update.

Before enabling a public endpoint, publish immutable versioned packages first,
then publish the manifest referencing them. Keep older compatible releases when
introducing new installation requirements. Real installer validation and automated
release-manifest publication remain separate release tasks.
