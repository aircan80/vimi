# Repository Guidelines

## Project Structure & Module Organization

This workspace currently contains no application source, tests, assets, or build manifests. The visible hidden directories are `.agents/`, `.codex/`, `.aws/`, and `.git/`; do not treat these as application modules or modify them as part of routine feature work.

When introducing code, organize related functionality together and document the resulting layout. Use clearly named directories such as `src/`, `tests/`, and `assets/` where appropriate to the chosen stack. Avoid creating empty scaffolding without an immediate purpose.

## Build, Test, and Development Commands

No build, test, lint, or local development commands are currently configured. Do not assume that commands such as `npm test` or `make build` are available.

When adding a toolchain, provide reproducible setup instructions and exact commands in `README.md`. Include dependency installation, local execution, production builds, and checks as applicable. Commit dependency manifests and lockfiles together.

## Coding Style & Naming Conventions

No language-specific style or formatter has been established. Follow the conventions of the selected language and use consistent indentation within each file. Prefer descriptive names for files, functions, and variables.

Introduce formatting and lint configuration alongside the first implementation that needs it. Keep changes focused and avoid unrelated formatting edits.

## Testing Guidelines

No testing framework or coverage threshold is configured. Add tests for new behavior and bug fixes using a framework appropriate to the implementation. Name tests after the behavior they verify, and document how to run them. Keep tests deterministic and independent of live credentials or services.

## Commit & Pull Request Guidelines

Git history is unavailable in this workspace, so existing commit conventions cannot be verified. Use short, imperative commit subjects, such as `Add project setup instructions`.

Pull requests should describe the change, its motivation, and validation performed. Link relevant issues and include screenshots for visible interface changes. Explicitly identify checks that could not be run.

## Security & Configuration

Never commit credentials, tokens, or local configuration secrets. Document required environment variables using placeholder values, and exclude generated artifacts and local configuration through appropriate ignore rules.
