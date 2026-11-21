# Automatic GitHub releases

Push the source to `main` or `master`. `.github/workflows/release.yml` builds the client and matching installer, checks them, creates a draft release, uploads the files, then publishes it. GitHub Actions supplies its built-in token with `contents: write`; no card or paid host is needed for this public repository.

The release contains `UtilityClient-Setup-Updated.exe`, `UtilityClient-Update.zip`, and `UtilityClient-Update.zip.sha256`. These exact update names are what existing bootstrapper versions look for. The release tag is derived from the artifact checksums. Repeating the same build does not create another release. An upload failure leaves a draft, allowing a retry without exposing an incomplete stable update.

The changed source, workflow and vendor dependencies need to be committed and pushed before Actions can run. Keep `vendor/baritone-unoptimized-fabric-1.19.0.jar` and `vendor/baritone-1.19.0-source.zip`, and their Linux copies, in the repository. The ignore rules now allow these required dependencies. The workflow stops early if they are missing. Allow read/write workflow permissions in the repository's Actions settings if its settings override workflow permissions.

For a local build that also publishes, provide `GH_TOKEN` or `GITHUB_TOKEN` to the build environment once, then run:

```powershell
powershell -ExecutionPolicy Bypass -File utilityclient/dist/build-and-publish.ps1 -Jdk "C:\Program Files\Java\jdk-25.0.5.1"
```

That script builds before submitting. For already-tested artifacts, preview the release first:

```powershell
python utilityclient/dist/publish-release.py --directory utilityclient/build/bootstrapper
```

Add `--publish` to submit the prepared artifacts. The repair build pipeline also submits after its final packaging step when `UTILITYCLIENT_AUTO_RELEASE=1` is set in the environment. Credentials are read only by the publisher and are never embedded in the installer or client.

The workflow and API publisher are saved locally. This checkout currently has no Git remote configured, and no GitHub publishing credential has been supplied here, so no release or remote workflow deployment has been performed.

## Optional direct manifest hosting

The bootstrapper also supports a public HTTPS `update.json` feed, with a package URL, SHA-256 and size. That support remains available if hosting changes later. The standard build uses GitHub release discovery until a manifest URL is configured. Scaleway/S3 tools are optional and need storage credentials; they are not required for automatic GitHub releases.
