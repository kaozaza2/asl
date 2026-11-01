# Contributing to ASL

Thanks for helping out. Issues and pull requests are welcome.

## Before you start

- Open an issue first for anything bigger than a small fix, so we can agree on the approach.
- Read [`docs/PROTOCOL.md`](docs/PROTOCOL.md) before touching anything that talks to the VM. It is the contract between the Android app and the guest agent; change both sides and the document together.
- AVF behavior differs between Android releases and OEMs. [`docs/AVF_NOTES.md`](docs/AVF_NOTES.md) records what is known; add to it when you find something new.

## Building and testing

See [Building](README.md#building) in the README. Before sending a pull request:

```sh
./gradlew :app:assembleDebug
docker run --rm -v "$PWD/guest/agent:/src" -w /src -e CGO_ENABLED=0 -e GOOS=linux -e GOARCH=arm64 \
  golang:1.23 sh -c 'test -z "$(gofmt -l .)" && go vet ./... && go build -o /dev/null .'
```

Say in the pull request which device and Android version you tested on. Changes to `vm/`, `guest/` or USB need a test on real AVF hardware; an emulator is not enough.

## Code style

- Kotlin follows the official style; Go is `gofmt`-clean.
- Comments are one line, or none. Put rationale in the commit message, not above the code.
- Prefer widening an existing function, or the stdlib/framework, over adding a helper with a single caller.
- Use one table describing variants rather than parallel constants.
- American English in code and prose (`color`, `behavior`, `canceled`).
- `terminal-emulator/` and `terminal-view/` are vendored from Termux; keep changes there minimal and marked `ASL fork:`.

## License

By contributing, you agree that your contributions are licensed under the [Apache License 2.0](LICENSE).
