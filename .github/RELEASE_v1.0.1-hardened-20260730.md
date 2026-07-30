# YJHack v1.0.1-hardened-20260730

This commit triggers the verified GitHub Release build from the hardened main tree.

The release workflow runs a clean Java 21 / Gradle 8.14.4 build, executes the full test suite, creates `YJHack-1.21.5.jar`, generates its SHA-256 checksum, and publishes both files to GitHub Releases.
