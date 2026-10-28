# Contributing

[Deutsch](Contributing-DE)

The full rules are in [CONTRIBUTING.md](https://github.com/VectoDE/Vectora-Network/blob/main/CONTRIBUTING.md). The short version:

1. Look at the [roadmap](https://github.com/users/VectoDE/projects/11) and pick or open an issue. Issues labelled `good first issue` are meant for newcomers.
2. For anything bigger than a small fix, wait for a reply on the issue before you start.
3. Branch from `main`, keep commits small, write [Conventional Commits](https://www.conventionalcommits.org/) and sign off every commit with `git commit -s`.
4. Run `./gradlew build`, then open a pull request with `Fixes #123`.

Things that matter in this project:

- Bedrock players must be able to use what you build.
- No new third-party plugins beyond the allowed base.
- Texts for players and admins in English and German.
- No secrets, IPs, player data, worlds or jars in commits.

## Editing this wiki

The wiki is generated from [`docs/wiki/`](https://github.com/VectoDE/Vectora-Network/tree/main/docs/wiki) in the repository. Edit the files there through a pull request. English pages have no suffix, German pages end in `-DE`. A workflow copies them into the wiki after a merge to `main`; direct edits in the wiki get overwritten.

Questions: [Discussions](https://github.com/VectoDE/Vectora-Network/discussions).
