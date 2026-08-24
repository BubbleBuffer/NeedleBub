# `.nbpack` format v1

An `.nbpack` is a data-only ZIP with a maximum expanded size of 128 MiB. Its
root contains exactly `manifest.json`, `tools.json`, `model.cact`, at least one
`LICENSE*` or `NOTICE*` text file, and optionally an icon in PNG, WebP, or SVG.

Nested paths, duplicate entries, symlinks, executable mode bits, scripts,
native libraries, bytecode, archives with unknown sizes, and unexpected files
are rejected before installation. Metadata entries are limited to 512 KiB.

The strict manifest declares a reverse-domain ID, semantic version, metadata
and license, exact Needle engine ABI, model size and SHA-256, a query template
containing one `{{input}}`, supported surfaces, and typed JSON Pointer output
mappings. Unknown fields fail validation.

Local imports are unverified and external-only. The automatic notification
listener resolves only the verified `de.x0bubbuff.needlebub.otp` catalogue pack.
