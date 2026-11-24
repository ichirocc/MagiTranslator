# Model files

MagiTranslator downloads its models only from this repository's release **`models-v1`**
(`ModelSources.BASE_URL`), never from the upstream author's repositories.

`models.tsv` lists every file: name, SHA-256, size in bytes, and the upstream URL it was copied from.
The app refuses a downloaded file whose SHA-256 differs (`Downloader` → `ModelSources.verify`).

## Filling the release (once)

Actions → **Mirror models** → *Run workflow*. It downloads each file from the upstream URL,
checks the SHA-256 against `models.tsv`, uploads it to the `models-v1` release, then downloads it back
from the release and checks it again. Files already in the release are skipped, so it can be re-run.

## Adding or replacing a model

1. Add/replace the line in `models.tsv` (`sha256sum <file>` for the hash).
2. Put the same hash in `ModelSources.java` (the CI test `ModelSourcesTest` fails if they differ).
3. Upload the file to the release (re-run the workflow, or `gh release upload models-v1 <file>`).

For a changed file use a new release tag (`models-v2`) and update `ModelSources.RELEASE_TAG`, so that
installed apps keep downloading the files their hashes were pinned for.
