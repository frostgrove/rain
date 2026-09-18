# Projection topology split runbook

This runbook scales one durable projection generation by replacing one **live** binary-prefix partition with its two
exact children. It is a one-way split: there is no merge, modulo resharing, cursor rewriting or automatic transfer of
causal state.

## Preconditions

Use the current checked `ProjectionCover`, contract revision and generation topology fingerprint to construct the
`ProjectionTopologyDeclaration`. The parent must be a live member of that exact durable cover. The command runs in a
caller-owned control/checkpoint transaction; the PostgreSQL adapter neither opens a private transaction nor retries it.

Quiesce scheduling for the parent and wait for any existing lease to expire or be released by its owner. Do not force a
lease token or edit the checkpoint row. Before retrying, clear every parent-local hold and halt through their normal
operator protocols. A `projection_hole`, including an acknowledged one, also blocks a split: its opaque sequence
cannot be assigned to a child without guessing.

## Execute and verify

Call `ProjectionTopologyStore.split(declaration, parent)` in the transaction. A successful result commits all of the
following together:

- two child checkpoints with the parent's exact delivered position and settlement evidence;
- the parent marked `RETIRED`, its checkpoint removed behind the parent fence, and immutable retirement lineage;
- the new checked cover fingerprint in both topology and generation state.

Deploy or activate workers with the updated cover and fingerprint, then run the two child lanes. An old parent runner
receives `Retired`; it cannot recreate or resume the parent checkpoint. Verify both children start at the inherited
cursor and that the durable live members equal the new exact cover.

`Busy(retryAt)` means the parent still has a live lease. `Blocked` reports the bounded counts of holds, holes and
halts that must be resolved. `ParentMissing` and `ContractDrift` are not retry signals: reload durable topology and
reconcile the caller's declaration or deployment before taking another action. A repeated command with the old
pre-split declaration is deliberately `ContractDrift`, because the durable generation fingerprint has changed.

## Never do this manually

Do not delete a parent checkpoint, insert child checkpoints, mutate the topology fingerprint, or copy a hold, letter,
hole or halt between partitions with SQL. Those operations break the transaction-bound ownership and cursor evidence
that prevents a sequence from disappearing or being processed concurrently by a parent and child.
