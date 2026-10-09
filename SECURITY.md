# Security Policy

AgentSpaces Model Wire is the set of entry types the fleet's model service,
chat memory snapshots, and usage entries travel as. It has no behavior of its
own: the records are plain Java, encoded by the canonical CBOR codec of the
AgentSpaces core and written into spaces by the Spring AI and Micronaut
LangChain4j bridges. It inherits the security model of the AgentSpaces core
(signed, self-certifying peers and agents; the threat model in the AgentSpaces
specification, SPEC §11).

## Reporting a vulnerability

Please report vulnerabilities **privately** via GitHub's security advisories:

> https://github.com/badmonkeyai/agentspaces-model-wire/security/advisories/new

A vulnerability in the AgentSpaces core libraries belongs in the core
repository's advisories instead:
https://github.com/badmonkeyai/agentspaces/security/advisories/new

A vulnerability in how a bridge handles these records (what a model server
executes, what a client trusts, who may read a conversation) belongs in that
bridge's repository:
https://github.com/badmonkeyai/agentspaces-springai/security/advisories/new or
https://github.com/badmonkeyai/agentspaces-micronaut/security/advisories/new

Do not open public issues for suspected vulnerabilities, and do not include
exploit details in public discussions until a fix is released.

We will acknowledge your report within five business days, keep you informed of
progress, credit you in the advisory unless you prefer otherwise, and
coordinate the disclosure timeline with you. There is currently no bug bounty
program.

Questions can be sent to `oss [at] badmonkey.ai`

## Scope

In scope: anything about the records themselves that lets an attacker violate
a guarantee the fleet claims. For example:
- a record whose canonical encoding is ambiguous, so two peers disagree about
  what was written (the golden vectors exist to catch this);
- a record validation gap that lets a malformed entry crash a reader rather
  than be refused;
- a field that carries more than it should (media bytes or arguments without
  the bounds the bridges assume).

What a model does with the content of a `ModelRequest` is a property of the
model and of the bridge that serves it, not of this artifact.

## Supported versions

During 0.x, only the latest minor release receives security fixes.
