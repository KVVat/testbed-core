# GitHub Actions Workflow Specifications

The distribution flow of this repository is divided into the following three workflows:

1. `Build Desktop Artifacts` (`.github/workflows/build.yaml`)
2. `Repackage and Zip Desktop Artifacts` (`.github/workflows/repackage-zip.yaml`)
3. `Create GitHub Release` (`.github/workflows/release.yaml`)

## 1. Build Desktop Artifacts

### Purpose
- Compiles the native desktop applications for each OS (Windows, macOS, Ubuntu) and saves them as actions artifacts.

### Triggers
- `workflow_dispatch` (Manual execution)
- `push tags: v*` (e.g. `v1.2.3`)

### Output Artifacts
- `TestbedCore-windows-latest`
- `TestbedCore-ubuntu-latest`
- `TestbedCore-macos-latest`

## 2. Repackage and Zip Desktop Artifacts

### Purpose
- Downloads the build artifacts, reorganizes files, and archives them into target-specific ZIP packages.
- On Linux and macOS, downloads are handled as `.tar.gz` to preserve executable permission bits during extraction.

### Triggers
- `workflow_run` (Triggered automatically upon successful completion of the `Build Desktop Artifacts` run)
- `workflow_dispatch` (Manual execution)

### Manual Run Input
- `build_run_id` (Required): Run ID of the matching `Build Desktop Artifacts` execution.

### Output Artifacts
- `release-zip-windows` (`TestbedCore-windows.zip`)
- `release-zip-ubuntu` (`TestbedCore-ubuntu.zip`)
- `release-zip-macos` (`TestbedCore-macos.zip`)

### Troubleshooting: macOS "Permission denied"
- Ensure that the ZIP archive was generated from the latest repackaging workflow (which correctly preserves executable bits).
- If the warning persists, it might be blocked by macOS Gatekeeper's quarantine attribute.
  - Run: `xattr -dr com.apple.quarantine TestbedCore.app`

## 3. Create GitHub Release

### Purpose
- Creates or updates a GitHub Release using the repackaged ZIP packages.

### Triggers
- `workflow_dispatch` Only (Manual execution)

### Inputs
- `package_run_id` (Required): Run ID of the target `Repackage and Zip Desktop Artifacts` execution.
- `tag_name` (Required): e.g. `v1.2.3`
- `release_name` (Optional): Falls back to `tag_name` if left empty.
- `draft` (Required): Flag to create as draft.
- `prerelease` (Required): Flag to flag as prerelease.
- `confirm` (Required): Must be set to `RELEASE` exactly.

### Prerequisites
- If the `tag_name` does not exist on remote, it will be automatically created on target `head_sha` from the matching `package_run_id` run.
- All three package archives must be present in the run, or the release step will fail:
  - `TestbedCore-windows.zip`
  - `TestbedCore-ubuntu.zip`
  - `TestbedCore-macos.zip`

### Token Permissions
- Utilizes `GITHUB_TOKEN` by default.
- If a repository secret named `RELEASE_TOKEN` exists, it takes precedence.

If encountering `HTTP 403: Resource not accessible by integration`:
1. Go to repository `Settings -> Actions -> General -> Workflow permissions` and toggle `Read and write permissions`.
2. Alternatively, configure a Personal Access Token (PAT) under `RELEASE_TOKEN`:
   - Classic PAT requirements: `repo`, `workflow` scopes.
   - Fine-grained PAT requirements: `Contents: Read and write`, `Workflows: Read and write` on target repository.

## Typical Operations Sequence

1. Push a release tag (`v*`) to trigger `Build Desktop Artifacts` automatically, or launch it manually.
2. The `Repackage and Zip` workflow will execute automatically on build success.
3. If repackaging fails or needs re-running, trigger `Repackage and Zip` manually with target `build_run_id`.
4. Trigger `Create GitHub Release` manually, passing the matching `package_run_id` and setting `confirm=RELEASE`.

## How to find a Run ID

- Navigate to the GitHub `Actions` tab.
- Click on the execution run in the list.
- Extract the numeric ID from the URL: `.../actions/runs/<Run_ID>`.
