# Contributing to AgentSpaces Model Wire

Thank you for your interest in contributing. AgentSpaces Model Wire is an
Apache 2.0 project from Bad Monkey, Inc., and everything in this repository
stays Apache 2.0.

This repository holds one small thing: the wire records of the fleet model
service, chat memory snapshots, and usage entries, shared by
[agentspaces-springai](https://github.com/badmonkeyai/agentspaces-springai)
and [agentspaces-micronaut](https://github.com/badmonkeyai/agentspaces-micronaut).
The behavior behind the records (the model servers and clients, the memory
stores, the usage sinks) lives in those projects; the coordination fabric
itself lives in the [AgentSpaces core repository](https://github.com/badmonkeyai/agentspaces).

## Before you start

- **Questions and ideas** are welcome as [GitHub issues](https://github.com/badmonkeyai/agentspaces-model-wire/issues).
- **Security issues** must not be filed as public issues. See
  [SECURITY.md](SECURITY.md) for private reporting.
- **Code of conduct:** by participating you agree to the
  [code of conduct](CODE_OF_CONDUCT.md).

## What a change here means

Every record in this artifact is an entry type on the wire, and the space
names an entry type by its fully qualified class name. So:

- **Never rename or move a record** inside a protocol version: peers on the
  current version would stop matching each other's entries. The package is
  `ai.badmonkey.agentspaces.springai.model.wire` for that reason, and it
  stays until a protocol version change.
- **Adding a component to a record** changes the canonical CBOR of every
  entry of that type. The canonical codec encodes absent fields as absent,
  so an added nullable field is readable by older peers only if they ignore
  unknown fields; treat any shape change as a protocol change and bump the
  version.
- **The golden vectors are the contract.** `WireGoldenTest` fails when the
  bytes change. After a deliberate change, regenerate them with
  `mvn test -Dwire.golden.write=true`, review the diff of
  `src/main/resources/wire-golden.properties`, and say in the pull request
  why the bytes changed. The consumers' suites read the same vectors from
  the jar.
- **No dependencies.** The records are plain Java (`String`, numbers,
  `List`, `Map`, `byte[]`), with no framework type and no annotation beyond
  the JDK's. Keep it that way; both consumers depend on this artifact
  precisely because it pulls nothing in.

## Building

Java 21 and Maven. `mvn clean verify` builds the jar and runs the golden
test; the test scope resolves `agentspaces-common` 0.2.0 from Maven Central
for the canonical codec. `-Pcentral-publish` attaches the sources and
javadoc jars and signs them, for releases.

## Pull requests

- One change per pull request, with the golden-vector diff when there is one.
- Keep the records' javadoc current: each component is documented, because
  the javadoc jar is what a consumer on the other framework reads.
- Run `mvn clean verify` before opening the pull request; CI runs the same
  on every push and pull request, plus a weekly dependency scan.
