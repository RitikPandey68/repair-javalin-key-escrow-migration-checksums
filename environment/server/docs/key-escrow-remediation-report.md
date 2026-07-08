# Key-Escrow Custody Remediation Report

**Document reference:** KE-REM-2024-0417
**Classification:** Regulated — Custodian Internal / Supervisory Disclosure
**Prepared for:** European Securities and Markets Authority (ESMA), Supervisory Technology Unit
**Prepared by:** Office of the Chief Custody Officer, Custodian Bank AG, Frankfurt
**Reporting period:** 2023-03-16 through 2024-04-17
**Report status:** Final — supersedes drafts KE-REM-2024-0392 and KE-REM-2024-0405

---

## 0. How to read this document

This report is the binding specification for the key-escrow migration replay that
the custody engineering team must perform to bring the escrow archive into a
regulator-acceptable state. It is written to be read end to end by the engineer who
implements the replay and by the supervisory reviewer who signs off on the resulting
custody manifest. Every normative rule in this document is stated in prose and, where
the prose could be read more than one way, is accompanied by a worked example in the
appendices. Wherever this document uses the words **MUST**, **MUST NOT**, **SHALL**,
**SHALL NOT**, **REQUIRED**, and **SHALL** in capitals, the requirement is normative
and its violation constitutes a reportable control deficiency under the supervisory
undertaking described in Section 2.

The migration replay reads a single nested export of the escrow archive, applies the
rules defined in Sections 5 through 9, and emits a canonical custody manifest. The
manifest is the artifact the regulator hashes; its SHA-256 digest is compared against
an expected checksum that the supervisory reviewer computes independently from the
same export using the same rules. If the two digests disagree, the migration is
rejected and must be replayed. For this reason the manifest is not merely a summary —
it is a canonical serialization, and its byte layout is as much a part of the
contract as its content. Sections 5 and 6 are therefore as important as Sections 7
through 9, even though they concern presentation rather than selection.

The remainder of this document is organized as follows. Section 1 gives the
background of the escrow programme and the custody archive. Section 2 records the
regulatory context and the supervisory undertaking under which this remediation is
performed. Section 3 sets out the incident timeline that triggered the remediation.
Section 4 defines the data model of the export and the manifest. Sections 5 through 9
state the four normative rule sets — canonical key ordering, value normalization,
the revocation-exception rules for custody grants, and the nested key-version folding
rules — together with the cross-cutting ordering constraints. Section 10 describes
the migration tables. Section 11 covers verification and sign-off. Section 12 is a
glossary. The appendices contain worked examples, the full rationale for each rule,
and the correspondence log with the supervisor.

---

## 1. Background

### 1.1 The escrow programme

Custodian Bank AG operates a regulated key-escrow programme on behalf of a portfolio
of institutional clients whose encrypted records must remain recoverable under a
narrow set of legally compelled circumstances. The programme has existed in some form
since 2011, but its current architecture dates from a 2019 platform migration that
introduced hardware-backed key management and a versioned key lifecycle. Under this
architecture, every piece of client key material that the bank holds in escrow is
identified by a stable key identifier and carries a chain of versions that records
each rotation the key has undergone over its lifetime. The escrow archive retains
every historical version rather than only the currently active one, because the
records a given version protected remain recoverable only with the key material of
that specific version. Discarding a historical version would render a slice of the
client's encrypted history permanently unrecoverable, which the escrow programme
exists precisely to prevent.

Key material never leaves the hardware boundary in the clear. Instead, each escrowed
key is protected by a wrapping certificate: the escrowed material is encrypted under
the public key of a wrapping certificate, and only the holder of the corresponding
private key — a custodian operating under a custody grant — can unwrap it. Wrapping
certificates are themselves rotated on a schedule, and a single escrowed key may be
protected by more than one wrapping certificate over its lifetime, though at the time
of this export each escrowed key is bound to exactly one wrapping certificate.

### 1.2 Custody grants

Access to escrowed material is authorized by custody grants. A custody grant names a
principal — a human custodian or an automated custody agent operating under a named
service identity — and authorizes that principal to exercise custody over a specified
wrapping certificate. A custody grant is the unit of authorization that the regulator
audits: when the supervisor asks "who could have recovered this client's material on
a given date," the answer is derived from the set of custody grants that were in
force on that date. It is therefore essential that the custody manifest reflect the
grants accurately, and in particular that it reflect the withdrawal of a grant when a
grant is revoked.

Grants are revoked for a variety of reasons: a custodian leaves the programme, a
service identity is decommissioned, a supervisory finding requires that a particular
principal's access be withdrawn, or a grant is discovered to have been issued in
error. Whatever the reason, a revoked grant is a grant that no longer authorizes
anything, and the custody manifest — which is a statement of who currently holds
custody — MUST NOT present a revoked grant as though it were in force. This is the
substance of the revocation-exception rule set in Section 7, and it is the single
most consequential selection rule in this document, because a manifest that carries a
revoked grant overstates the population of principals with custody and thereby
misrepresents the bank's control posture to the regulator.

### 1.3 The custody archive and its export

The custody archive is the authoritative store of escrowed keys, their versions, the
wrapping certificates, and the custody grants. For the purposes of this remediation,
the archive is exported into a single nested JSON document. The export is nested
because the data is naturally nested: an escrowed key contains its versions, and the
certificates and grants reference keys and certificates by identifier. The export is
not itself the manifest; the manifest is derived from the export by applying the
rules in this document. The export may contain data in any internal order — the
archive does not guarantee that keys, versions, certificates, or grants appear in any
particular sequence — and it may contain revoked grants and superseded versions,
because it is a faithful dump of the archive rather than a curated view. The whole
point of the migration replay is to turn this faithful-but-unordered dump into the
canonical, curated manifest the regulator expects.

---

## 2. Regulatory context and the supervisory undertaking

### 2.1 Applicable framework

The escrow programme is supervised under the bank's authorization as a custodian of
regulated client records. The relevant supervisory expectations are drawn from the
following instruments, cited here in the form used throughout this report:

- **SUP-CUST-7** — Supervisory Statement on Custody of Cryptographic Key Material,
  which requires that a custodian be able to produce, on demand, an accurate and
  reproducible statement of the custody posture of every escrowed key.
- **SUP-CUST-7.3** — the sub-paragraph of SUP-CUST-7 that requires custody statements
  to be *reproducible*, meaning that two parties computing the statement from the same
  underlying archive MUST arrive at byte-identical results.
- **SUP-CUST-11** — Supervisory Statement on Revocation and Withdrawal of Access,
  which requires that withdrawn authorizations be reflected promptly and completely in
  any statement of current access.
- **REC-AUDIT-4** — the recordkeeping standard requiring that historical states of
  key material remain auditable, which is the basis for the requirement that every
  historical key version be retained and reflected.

The reproducibility requirement of SUP-CUST-7.3 is the reason the manifest must be a
*canonical* serialization. If the manifest were merely "a JSON document containing the
right data," two correct implementations could produce documents that differ in key
ordering or value formatting, and their digests would differ, defeating the
independent-verification control. Canonicalization removes that degree of freedom: a
canonical manifest has exactly one correct byte layout for a given export, so any two
correct implementations produce the same digest.

### 2.2 The undertaking

Following the incident described in Section 3, the bank gave the supervisor a written
undertaking to (a) replay the entire escrow archive through a corrected migration
pipeline, (b) produce a canonical custody manifest whose digest the supervisor could
independently verify, and (c) document the rules governing that manifest in a form
that an independent engineer could implement without further guidance. This report
discharges obligation (c) and specifies the rules for obligations (a) and (b). The
undertaking further requires that the migration pipeline not embed the expected digest
as a constant — the digest MUST be derived from the export and the rules, never
asserted — so that the pipeline genuinely recomputes the manifest rather than merely
reproducing a stored answer. An implementation that hard-codes the expected digest,
or that special-cases the sample export to produce a known output, is a control
deficiency in its own right regardless of whether the output happens to be correct.

---

## 3. Incident timeline

The remediation was triggered by a supervisory finding that the bank's then-current
custody statements were unreliable. The finding arose from a routine reconciliation in
which the supervisor recomputed a custody statement from the archive export and found
that it disagreed with the statement the bank had filed. The disagreement had three
distinct causes, each of which corresponds to one of the rule sets in this report and
each of which the corrected migration pipeline must address.

**2023-03-16.** The supervisor requested the quarterly custody statement and the
underlying archive export. The bank filed both.

**2023-04-02.** During reconciliation the supervisor observed that the filed statement
listed principals whose grants had been revoked. On inquiry, the bank determined that
the migration pipeline copied every custody grant from the export into the statement
without consulting the grant's revocation flag. This is the *revocation defect*; its
correction is specified in Section 7.

**2023-05-11.** The supervisor observed that the filed statement listed, for each
escrowed key, only the most recent key version. Historical versions were absent. The
bank determined that the migration pipeline's version reconciliation kept only the
latest version of each key and discarded the rest, on the mistaken assumption that
only the active version was of interest. Because the escrow programme exists to keep
historical records recoverable, the absence of historical versions from the custody
statement understated the material the bank held and broke the audit trail required by
REC-AUDIT-4. This is the *version-folding defect*; its correction is specified in
Section 8.

**2023-07-20.** The supervisor's independent recomputation of the statement's digest
disagreed with the bank's even after the revocation and version defects were
understood, because the two implementations serialized the statement's JSON with
different key orderings. The bank's pipeline emitted object keys in the order in which
its code happened to populate them; the supervisor's reference implementation emitted
them in a canonical order. Because SUP-CUST-7.3 requires byte-identical
reproducibility, the ordering discrepancy was itself a finding. This is the
*canonicalization defect*; its correction is specified in Sections 5 and 6.

**2023-09-01.** The bank gave the written undertaking described in Section 2.2.

**2023-11 through 2024-03.** The bank and the supervisor iterated on the precise
statement of the canonicalization, revocation and folding rules, arriving at the
definitions in Sections 5 through 9. The correspondence is logged in Appendix D.

**2024-04-17.** This report was finalized and the corrected migration pipeline
scheduled for its supervised replay.

The three defects are independent: an implementation may correct any one of them
without correcting the others, and each produces an incorrect manifest on its own. A
manifest is correct only when all three defects are absent — that is, when revoked
grants are excluded, every version is folded in the defined order, and the JSON is
serialized in canonical key order with normalized values. The verification in Section
11 will fail on any manifest that exhibits any one of the three defects.

---

## 4. Data model

This section defines the shape of the export the pipeline reads and the manifest it
produces. The rules in later sections are stated in terms of the entities defined
here.

### 4.1 The export

The export is a single JSON object with the following top-level members:

- `exportId` — a string uniquely identifying this export instance. It is carried into
  the manifest as provenance (see Section 5.2).
- `jurisdiction` — a string naming the supervisory jurisdiction. It is not carried into
  the manifest; it is metadata for the archive's own bookkeeping.
- `escrowedKeys` — an array of escrowed-key objects (Section 4.2).
- `wrappingCertificates` — an array of wrapping-certificate objects (Section 4.3).
- `custodyGrants` — an array of custody-grant objects (Section 4.4).

The arrays appear in no guaranteed order. The pipeline MUST NOT assume that
`escrowedKeys` is sorted by key identifier, that `custodyGrants` is sorted by grant
identifier, or that the versions within an escrowed key are sorted by version number.
Ordering is imposed by the pipeline according to the rules in Section 9, not inherited
from the export.

### 4.2 Escrowed keys

Each escrowed-key object has:

- `keyId` — a stable string identifier for the key material. Key identifiers in the
  archive are recorded with mixed case for historical reasons; the manifest normalizes
  them (Section 6.2).
- `versions` — an array of key-version objects (Section 4.5). An escrowed key has at
  least one version and may have many. Versions may appear in the array in any order.

### 4.3 Wrapping certificates

Each wrapping-certificate object has:

- `certId` — a stable string identifier for the certificate.
- `subject` — the distinguished name of the certificate subject, recorded as a string.
  Subjects in the archive contain inconsistent internal whitespace for historical
  reasons; the manifest normalizes this (Section 6.3).
- `keyId` — the identifier of the escrowed key this certificate protects.
- `notAfterEpoch` — the certificate's expiry, expressed as seconds since the Unix
  epoch.

### 4.4 Custody grants

Each custody-grant object has:

- `grantId` — a stable string identifier for the grant.
- `principal` — the identity the grant authorizes, recorded as a string. Principals in
  the archive sometimes carry incidental leading or trailing whitespace; the manifest
  trims this (Section 6.4).
- `certId` — the identifier of the wrapping certificate the grant applies to.
- `revoked` — a boolean. When `true`, the grant has been withdrawn and MUST be excluded
  from the manifest (Section 7).
- `grantedEpoch` — the moment the grant was issued, expressed as seconds since the Unix
  epoch.

### 4.5 Key versions

Each key-version object has:

- `version` — a positive integer that increases monotonically with each rotation. It is
  the primary key of the version within its escrowed key.
- `algorithm` — the cryptographic algorithm the version used, recorded as a string with
  inconsistent case in the archive; the manifest normalizes it to upper case
  (Section 6.5).
- `custodyStatus` — the lifecycle state of the version (for example, `active` or
  `retired`), recorded as a lower-case string. It is carried into the manifest
  verbatim.
- `createdEpoch` — the moment the version was created, expressed as seconds since the
  Unix epoch.

### 4.6 The manifest

The manifest is a single JSON object. Its members and their canonical order are
defined in Section 5. Informally, it carries provenance (`generatedFrom`,
`manifestVersion`), the folded key versions (`keyVersions`), the surviving custody
grants (`custodyGrants`), the wrapping certificates (`wrappingCertificates`), and a
record count (`recordCount`). The precise content and ordering rules are the subject
of the sections that follow.

---

## 5. Rule set A — canonical key ordering

### 5.1 The governing principle

The reproducibility requirement of SUP-CUST-7.3 requires that the manifest have
exactly one correct byte layout for a given export. JSON objects are unordered by the
standard, but a *serialized* JSON object has a concrete key order in its bytes, and it
is those bytes the digest is computed over. The canonical ordering rule removes the
ambiguity by fixing the key order at every object level.

**Rule A-1 (canonical key order).** In the serialized manifest, the members of every
JSON object — the top-level manifest object and every nested object at every depth —
MUST appear in **lexicographic ascending order of their key names**, compared as
Unicode code-point sequences. This is the ordering produced by a standard ascending
string comparison of the key names. It is *not* the order in which the implementation
happens to populate its data structures, and it is *not* the order in which the keys
appear in the export.

The practical consequence for an implementer is that the serializer MUST impose the
key order explicitly. It is not sufficient to build an object in the desired order and
rely on the serializer to preserve insertion order; that produces whatever order the
building code used, which is the very defect Section 3 records for 2023-07-20. The
serializer MUST sort keys, or equivalently MUST be configured so that map entries are
emitted in sorted key order regardless of the order in which they were inserted. A
serializer left in its default mode — emitting object members in insertion order —
does not satisfy Rule A-1 and produces a manifest whose digest will not match the
expected value.

### 5.2 The top-level manifest members

The top-level manifest object has exactly these six members. Their names, and
therefore their canonical (lexicographic) order in the serialized manifest, are:

1. `custodyGrants` — the array of surviving custody grants (Section 7, Section 9.2).
2. `generatedFrom` — the `exportId` of the export the manifest was derived from.
3. `keyVersions` — the array of escrowed keys with their folded versions
   (Section 8, Section 9.1).
4. `manifestVersion` — the integer schema version of the manifest, currently `1`.
5. `recordCount` — the total number of folded key versions across all keys
   (Section 8.4).
6. `wrappingCertificates` — the array of wrapping certificates (Section 9.3).

Note carefully that this is the *lexicographic* order of the six member names, which
is the order they MUST appear in the serialized manifest. It is deliberately not a
"logical" order such as "provenance first, then data, then counts"; a logical order
would be a matter of taste and taste is not reproducible. Lexicographic order is
mechanical and therefore reproducible, which is exactly what SUP-CUST-7.3 requires.
An implementer who emits, for example, `manifestVersion` first because it "feels like
a header" has violated Rule A-1.

### 5.3 Nested objects

Rule A-1 applies recursively. Each element of `custodyGrants` is an object whose
members MUST appear in lexicographic order: `certId`, `grantId`, `grantedEpoch`,
`principal`. Each element of `keyVersions` is an object whose members MUST appear in
lexicographic order: `keyId`, `versions`. Each element of the inner `versions` array
is an object whose members MUST appear in lexicographic order: `algorithm`,
`createdEpoch`, `custodyStatus`, `version`. Each element of `wrappingCertificates` is
an object whose members MUST appear in lexicographic order: `certId`, `keyId`,
`notAfterEpoch`, `subject`.

The recursion matters because a serializer that sorts only the top-level object, or
that sorts nothing and relies on insertion order that happens to be lexicographic at
some levels but not others, will produce a manifest that is canonical in part and
non-canonical elsewhere. The only robust way to satisfy Rule A-1 is a serializer
configuration that sorts *every* object's keys, applied uniformly.

### 5.4 Arrays are ordered by content, not by key

Rule A-1 concerns the ordering of *object members*, which are identified by key name.
It says nothing about the ordering of *array elements*, which have no key names. The
ordering of array elements is governed separately by the ordering constraints in
Section 9 (grants by grant identifier, keys by normalized key identifier, versions by
version number, certificates by certificate identifier). An implementer must not
conflate the two: sorting object keys does not sort array elements, and sorting array
elements does not sort object keys. Both are required, by different rules.

---

## 6. Rule set B — value normalization

Canonical ordering fixes the arrangement of the manifest; value normalization fixes
its content down to the byte. Two archives may record the same fact with different
incidental formatting — mixed case, stray whitespace — and the manifest must erase
those incidental differences so that the same fact always serializes to the same
bytes. Each normalization below is a separate rule; all apply.

### 6.1 Scope

Normalization applies to specific fields named in the rules below. Fields not named in
a normalization rule are carried into the manifest verbatim, exactly as they appear in
the export. In particular, `custodyStatus`, `grantId`, `certId` (as it appears on a
certificate), the numeric epoch fields, and the integer `version` are carried
verbatim.

### 6.2 Key identifiers are lower-cased

**Rule B-1.** Every key identifier — wherever it appears in the manifest, whether as
the `keyId` of an escrowed key, the `keyId` of a wrapping certificate, or (implicitly)
anywhere a key is referenced — MUST be normalized to lower case before serialization.
The archive records key identifiers with mixed case for historical reasons
(`KMS-ROOT-Alpha`), and the manifest MUST record them lower-cased (`kms-root-alpha`).
Because key identifiers are also the sort key for the ordering of `keyVersions`
(Section 9.1), the lower-casing MUST be applied *before* the sort, so that the sort
operates on the normalized form. Sorting on the raw mixed-case form and then
lower-casing produces a different order (upper-case letters sort before lower-case in
code-point order), which would violate Section 9.1.

### 6.3 Certificate subjects have whitespace collapsed

**Rule B-2.** The `subject` distinguished name of each wrapping certificate MUST be
normalized by trimming leading and trailing whitespace and collapsing every internal
run of one or more whitespace characters to a single ASCII space. The archive records
subjects with inconsistent internal spacing — sometimes `O=Custodian Bank`, sometimes
`O=Custodian  Bank` with two spaces after a comma, sometimes with no space — and the
manifest MUST record a single canonical spacing. The collapse applies to any run of
whitespace, including spaces introduced around the comma separators of the
distinguished name; the rule does not attempt to parse the distinguished name, it
simply collapses whitespace runs uniformly across the whole string.

### 6.4 Principals are trimmed

**Rule B-3.** The `principal` of each surviving custody grant MUST have leading and
trailing whitespace removed. Unlike subjects (Rule B-2), principals are *trimmed only*
— internal whitespace, if any, is left intact, because a principal is an opaque
identity string whose internal characters are significant, whereas a distinguished
name's internal spacing is incidental formatting. The distinction is deliberate: do
not collapse internal whitespace in principals, and do not merely trim subjects.

### 6.5 Algorithms are upper-cased

**Rule B-4.** The `algorithm` of each key version MUST be normalized to upper case
before serialization. The archive records algorithm names in lower case
(`aes-256-gcm`); the manifest MUST record them upper-cased (`AES-256-GCM`). This
applies to every folded version, not only the active one.

### 6.6 Why normalization precedes ordering and selection

Normalization, ordering and selection interact, and the order in which they are
applied matters. The pipeline MUST normalize before it orders, because key identifiers
are the sort key for `keyVersions` (Section 6.2). The pipeline applies selection
(revocation exclusion, Section 7) independently of normalization; a grant's
`revoked` flag is not normalized because it is a boolean, and the decision to exclude a
revoked grant is made on the raw flag. The recommended pipeline order is: (1) parse the
export; (2) normalize the fields named in this section; (3) select the surviving
grants per Section 7 and fold the versions per Section 8; (4) order the arrays per
Section 9; (5) serialize with canonical key ordering per Section 5. An implementation
that applies these steps in a different order may still be correct if it is careful
about the key-identifier-before-sort dependency, but the recommended order avoids that
pitfall by construction.

---

## 7. Rule set C — revocation exceptions for custody grants

### 7.1 The rule

**Rule C-1 (revocation exclusion).** A custody grant whose `revoked` flag is `true`
MUST NOT appear in the manifest. Only grants whose `revoked` flag is `false` are
carried into the `custodyGrants` array. This is an exclusion, not an annotation: a
revoked grant is *absent* from the manifest, not present-with-a-flag. The manifest is a
statement of grants in force, and a revoked grant is not in force.

### 7.2 Why exclusion and not annotation

An earlier draft of this rule proposed carrying revoked grants into the manifest with a
`revoked: true` marker, on the theory that a reader could then see the full history.
The supervisor rejected this: SUP-CUST-11 requires that a statement of *current* access
reflect withdrawals *completely*, and a document that lists a revoked principal — even
with a marker — invites a reader to count that principal among those with access. The
audit trail of revocations lives in the archive, not in the current-custody manifest.
The manifest answers exactly one question — "who holds custody now" — and a revoked
grant is not part of that answer. Hence exclusion.

### 7.3 The revocation flag is authoritative

The `revoked` flag on the grant is the sole determinant of whether the grant is
excluded. The pipeline MUST NOT attempt to infer revocation from any other field — not
from the grant's age, not from whether the referenced certificate has expired, not from
whether the principal appears in any other grant. A grant with `revoked: false` is
carried even if its certificate has expired; certificate expiry is a separate matter
recorded in `wrappingCertificates` and does not remove a grant. Conversely a grant with
`revoked: true` is excluded even if it was issued yesterday. The flag says what it says.

### 7.4 Interaction with certificates and keys

Excluding a revoked grant removes only the grant. It does not remove the wrapping
certificate the grant referenced, nor the escrowed key that certificate protects.
Certificates and keys are carried into the manifest on their own terms (a certificate
appears if it is in the export; a key appears if it is in the export), independently of
whether any grant against them survived. It is entirely possible, and not an error, for
a certificate to appear in `wrappingCertificates` while no surviving grant references
it, because its only grants were revoked. The manifest records the certificate's
existence and expiry regardless; it records only the *surviving* grants against it.

### 7.5 Counting

The revocation exclusion affects the `custodyGrants` array only. It does not affect
`recordCount`, which counts folded key versions (Section 8.4), nor the population of
`keyVersions` or `wrappingCertificates`. An implementer must not, for example, reduce
`recordCount` when a grant is revoked; `recordCount` is unrelated to grants.


---

## 8. Rule set D — nested key-version folding

### 8.1 The rule

**Rule D-1 (complete folding).** For every escrowed key, *every* version present in
the export MUST be folded into the manifest. No version is dropped. In particular, the
pipeline MUST NOT keep only the most recent version of a key and discard the rest; it
MUST retain the full chain. This is the correction of the version-folding defect
recorded for 2023-05-11 in Section 3.

The word "folding" is used deliberately in preference to "including." To fold a key's
versions is to gather the whole version chain into the single `versions` array of the
key's manifest entry, in the defined order (Section 9.4), producing one manifest entry
per key whose `versions` array carries the complete history. Folding is not
deduplication and not summarization: the number of version objects in the manifest for
a given key equals the number of version objects for that key in the export.

### 8.2 Why every version

The escrow programme exists to keep historical client records recoverable, and a
given historical record is recoverable only with the key version that protected it.
The custody manifest is therefore not a statement about the *current* key alone; it is
a statement about all the key material the bank holds, including material that
protects only historical records. REC-AUDIT-4 requires that these historical states
remain auditable, which they cannot be if the manifest lists only the active version.
An implementer who reasons "only the active version matters, the rest are superseded"
has made exactly the mistake the 2023-05-11 finding records: superseded versions are
not discarded material, they are retained material that protects retained records.

### 8.3 Folding does not deduplicate or merge

Each version is folded as its own object. Versions are not merged, even when they
share an algorithm or a custody status. Two retired versions of the same key that both
used `AES-256-CBC` produce two separate version objects in the folded array, not one.
The `version` integer distinguishes them and is always present. An implementer must
not collapse versions that "look the same."

### 8.4 The record count

**Rule D-2 (record count).** The top-level `recordCount` member of the manifest is the
total number of folded key versions across all escrowed keys — that is, the sum over
every escrowed key of the number of versions that key has in the export. It is not the
number of keys, not the number of surviving grants, and not the number of
certificates. If the export has three keys with three, two and one versions
respectively, `recordCount` is six. Because Rule D-1 requires that every version be
folded, `recordCount` also equals the total number of version objects that appear
across all `versions` arrays in the manifest; the two counts are the same number by
construction, and a discrepancy between them indicates that a version was dropped in
violation of Rule D-1.

### 8.5 Interaction with custody status

A version's `custodyStatus` (for example `active` or `retired`) is carried into the
manifest verbatim (it is not normalized; see Section 6.1) and does *not* affect whether
the version is folded. A retired version is folded exactly as an active one is. The
`custodyStatus` is descriptive metadata about the version's lifecycle state; it is not
a selection criterion. This is the version-level analogue of the point made in Section
7.3 about the revocation flag: selection is governed by the specific rule that governs
it (here, Rule D-1, which selects *all* versions), not by an unrelated status field.

---

## 9. Ordering constraints

Section 5 governs the ordering of object *members*; this section governs the ordering
of array *elements*. Both are required for a canonical manifest.

### 9.1 Escrowed keys are ordered by normalized key identifier

**Rule E-1.** The elements of the top-level `keyVersions` array MUST be ordered by the
escrowed key's *normalized* (lower-cased, per Rule B-1) key identifier, ascending, by
Unicode code-point comparison. Because normalization precedes ordering (Section 6.6),
the sort operates on the lower-cased identifier. A key whose raw identifier is
`KMS-ROOT-Alpha` sorts as `kms-root-alpha`, which precedes `kms-root-mu`, which
precedes `kms-root-zeta`. Sorting on the raw mixed-case form would place all
upper-case-initial segments before lower-case ones and produce a different order; that
is a violation of this rule.

### 9.2 Custody grants are ordered by grant identifier

**Rule E-2.** The elements of the top-level `custodyGrants` array — after the revoked
grants have been excluded per Section 7 — MUST be ordered by `grantId`, ascending, by
Unicode code-point comparison. Grant identifiers are recorded in a fixed-width zero-
padded form (`CG-0001`, `CG-0002`, ...) precisely so that lexicographic ordering
coincides with numeric ordering; the pipeline sorts them lexicographically as strings
and does not need to parse the numeric suffix.

### 9.3 Wrapping certificates are ordered by certificate identifier

**Rule E-3.** The elements of the top-level `wrappingCertificates` array MUST be ordered
by `certId`, ascending, by Unicode code-point comparison. As with grant identifiers,
certificate identifiers are zero-padded (`WC-001`, `WC-002`, ...) so that lexicographic
ordering coincides with numeric ordering.

### 9.4 Versions within a key are ordered by version number

**Rule E-4.** Within each escrowed key's folded `versions` array, the version objects
MUST be ordered by their integer `version`, ascending. This is a numeric ordering of an
integer field, not a lexicographic ordering of a string; version `2` precedes version
`10`. Because Rule D-1 requires that every version be folded, the ordered array runs
from the earliest version to the latest with no gaps introduced by the pipeline
(gaps that exist in the archive, if any, are preserved — the pipeline does not
synthesize missing versions, it orders the versions that exist).

### 9.5 Summary of orderings

To summarize the two orthogonal ordering rules: *object members* are ordered
lexicographically by key name at every level (Section 5); *array elements* are ordered
by the content key appropriate to the array — normalized key identifier for
`keyVersions`, `grantId` for `custodyGrants`, `certId` for `wrappingCertificates`, and
integer `version` for the inner `versions` arrays (this section). A canonical manifest
satisfies both sets of rules simultaneously.

---

## 10. Migration tables

The replay also writes the archive into an H2-backed relational store so that the
custody engineering team can query it during the supervised replay. The store lives at
`./data/migration` relative to the working directory and carries three tables that
mirror the export's three collections. The tables are a working artifact of the
replay; the *manifest* is the regulated deliverable, and the tables are not themselves
hashed or filed. Nonetheless the replay MUST write them, because the supervisor's
on-site reconciliation queries them directly.

### 10.1 key_versions

One row per folded key version — that is, one row for every version of every escrowed
key, consistent with Rule D-1. The columns are the key identifier, the version number,
the algorithm, the custody status, and the creation epoch. Because every version is
written, the row count of this table equals the manifest's `recordCount`.

### 10.2 wrapping_certificates

One row per wrapping certificate, keyed by certificate identifier, carrying the
subject, the protected key identifier, and the expiry epoch.

### 10.3 custody_grants

One row per custody grant *as it appears in the export*, including revoked grants. The
table is a faithful mirror of the archive's grants, so revoked grants are written to
the table with their `revoked` flag set. The exclusion of revoked grants (Section 7) is
a property of the *manifest*, not of the table: the manifest is the current-custody
statement and excludes revoked grants, while the table is the working mirror and
retains them so that the supervisor can audit the revocations themselves. An
implementer must not confuse the two — writing all grants to the table is correct, and
carrying all grants into the manifest is the revocation defect.

---

## 11. Verification and sign-off

### 11.1 The digest check

The supervisor computes the SHA-256 digest of the canonical manifest independently from
the same export using the rules in this document, and compares it against the digest of
the manifest the bank's pipeline produced. The two MUST match byte-for-byte. The
expected digest is recorded in the checksum artifact accompanying the export; that
artifact is regenerated from the reference implementation's output and is not embedded
in the pipeline (Section 2.2). A mismatch indicates that the pipeline violated at least
one rule in this document; the mismatch alone does not say which, which is why the
verification suite also checks each rule individually (Section 11.2).

### 11.2 Rule-by-rule checks

Because a digest mismatch is opaque, the verification suite exercises each rule set
separately, so that a failure points at the specific defect:

- A *canonical ordering* check parses the manifest and asserts that the members of the
  top-level object, and of representative nested objects, appear in lexicographic
  ascending order (Rule set A). A pipeline that emits members in insertion order fails
  this check.
- A *revocation* check asserts that no grant identifier belonging to a revoked grant
  appears in the manifest, and that every non-revoked grant does appear (Rule set C). A
  pipeline that carries revoked grants fails this check.
- A *version-folding* check asserts that, for a key with multiple versions in the
  export, every version appears in the manifest in ascending version order, and that
  `recordCount` equals the total folded version count (Rule set D, Rule E-4). A
  pipeline that keeps only the latest version fails this check.
- A *value-normalization* check asserts that key identifiers are lower-cased, algorithms
  upper-cased, subjects whitespace-collapsed and principals trimmed (Rule set B).

Each check is independent, so a pipeline that corrects some but not all of the three
2023 defects fails the checks corresponding to the defects it has not yet corrected.
Only a pipeline that satisfies every rule passes every check and produces the digest
the supervisor expects.

### 11.3 Sign-off

Sign-off requires that every rule-by-rule check pass and that the digest match. The
supervisory reviewer signs the manifest, not the pipeline; but a manifest can be signed
only if it was produced by a pipeline that recomputes it from the export and the rules,
because the reviewer's independent recomputation is what the sign-off attests to.

---

## 12. Glossary

**Archive.** The authoritative store of escrowed keys, versions, wrapping certificates
and custody grants.

**Canonical serialization.** A serialization with exactly one correct byte layout for a
given input, achieved here by fixing object-member order (Section 5) and normalizing
values (Section 6).

**Custody grant.** An authorization of a named principal to exercise custody over a
wrapping certificate. May be revoked.

**Custody manifest.** The regulated deliverable: a canonical JSON statement of current
custody, derived from the export by the rules in this document.

**Escrowed key.** A piece of client key material held in escrow, identified by a stable
identifier and carrying a chain of versions.

**Export.** A single nested JSON dump of the archive, the input to the migration replay.

**Folding.** Gathering a key's complete version chain into the single `versions` array
of its manifest entry, in ascending version order (Section 8, Rule E-4).

**Normalization.** Erasing incidental formatting differences (case, whitespace) so that
the same fact serializes to the same bytes (Section 6).

**Principal.** The identity a custody grant authorizes.

**Revocation.** The withdrawal of a custody grant, recorded by the grant's `revoked`
flag and causing the grant's exclusion from the manifest (Section 7).

**Wrapping certificate.** The certificate under whose public key an escrowed key's
material is encrypted; the unit of protection a custody grant authorizes access to.

---

## Appendix A — Worked examples

This appendix works the rules of Sections 5 through 9 through concrete inputs so that
an implementer can check their understanding against a fully spelled-out result. The
examples use the same shape of data as the accompanying export but are chosen to
illustrate each rule crisply. Where an example refers to "the accompanying export" it
means the `assets/key-escrow-export.json` file distributed with this report.

### A.1 A single key with three versions

Consider an escrowed key recorded in the archive as `KMS-ROOT-Zeta` with three
versions. In the export the versions appear in the order they were pulled from the
archive, which happens to be version 3 first, then version 1, then version 2:

- version 3, algorithm `aes-256-gcm`, custody status `active`, created at epoch
  1710460800;
- version 1, algorithm `aes-128-gcm`, custody status `retired`, created at epoch
  1678924800;
- version 2, algorithm `aes-256-cbc`, custody status `retired`, created at epoch
  1694649600.

The folding rule (Rule D-1) requires that all three versions appear in the manifest;
the version-ordering rule (Rule E-4) requires that they appear in ascending version
order, that is version 1, then version 2, then version 3; the normalization rules
require that the key identifier be lower-cased to `kms-root-zeta` (Rule B-1) and each
algorithm be upper-cased (Rule B-4); and the canonical-key-order rule (Rule A-1)
requires that within each version object the members appear as `algorithm`,
`createdEpoch`, `custodyStatus`, `version`, and within the key object as `keyId`,
`versions`. The correct manifest entry for this key is therefore, in prose: a key
object whose `keyId` is `kms-root-zeta` and whose `versions` array has three elements,
the first being version 1 with algorithm `AES-128-GCM`, custody status `retired`,
created epoch 1678924800; the second being version 2 with algorithm `AES-256-CBC`,
custody status `retired`, created epoch 1694649600; the third being version 3 with
algorithm `AES-256-GCM`, custody status `active`, created epoch 1710460800.

A pipeline exhibiting the version-folding defect of Section 3 would produce a `versions`
array with only one element — version 3, the latest — because it kept only the most
recent version. That output violates Rule D-1 (a version was dropped) and, incidentally,
also produces the wrong `recordCount` (it would count one version for this key instead
of three). The verification's version-folding check (Section 11.2) fails on such output
because it asserts three versions, in ascending order, for this key.

### A.2 The effect of normalization on ordering

Consider three escrowed keys recorded in the archive as `KMS-ROOT-Zeta`,
`KMS-ROOT-Alpha` and `KMS-ROOT-Mu`, appearing in the export in that order. Rule E-1
orders the `keyVersions` array by the *normalized* key identifier, so the pipeline
first lower-cases the identifiers to `kms-root-zeta`, `kms-root-alpha` and
`kms-root-mu`, then sorts ascending: `kms-root-alpha`, `kms-root-mu`, `kms-root-zeta`.
The manifest lists the keys in that order regardless of the order they appeared in the
export.

Observe why normalization must precede the sort. If the pipeline sorted the raw
identifiers `KMS-ROOT-Zeta`, `KMS-ROOT-Alpha`, `KMS-ROOT-Mu` and then lower-cased, the
raw sort would compare the segment after the second hyphen — `Zeta`, `Alpha`, `Mu` —
all beginning with upper-case letters, and would produce `KMS-ROOT-Alpha`,
`KMS-ROOT-Mu`, `KMS-ROOT-Zeta`, which happens to coincide with the normalized order in
this particular example because the initial letters are already in alphabetical order.
The coincidence is not guaranteed in general: consider a fourth key `KMS-ROOT-beta`
recorded with a lower-case segment. Sorted raw, `KMS-ROOT-Zeta` (code point of `Z` is
0x5A) precedes `KMS-ROOT-beta` (code point of `b` is 0x62), because all upper-case
letters precede all lower-case letters in code-point order; but sorted normalized,
`kms-root-beta` precedes `kms-root-zeta`. The two orders differ. Rule B-1 and Rule E-1
together require the normalized order, so the pipeline must lower-case first. This is
the pitfall Section 6.6 warns about, and it is why the recommended pipeline order
normalizes before it sorts.

### A.3 Revocation exclusion with surviving grants

Consider five custody grants in the export:

- `CG-0007`, principal `  ops.custodian@custodian-bank.example  ` (note the
  surrounding whitespace), certificate `WC-004`, not revoked, granted epoch 1710547200;
- `CG-0002`, principal `regulator.observer@esma.europa.example`, certificate `WC-001`,
  revoked, granted epoch 1701216000;
- `CG-0001`, principal `primary.custodian@custodian-bank.example`, certificate
  `WC-001`, not revoked, granted epoch 1669939200;
- `CG-0005`, principal `backup.custodian@custodian-bank.example`, certificate `WC-002`,
  revoked, granted epoch 1704153600;
- `CG-0003`, principal `secondary.custodian@custodian-bank.example`, certificate
  `WC-002`, not revoked, granted epoch 1704240000.

Rule C-1 excludes the two revoked grants, `CG-0002` and `CG-0005`, leaving three
surviving grants: `CG-0001`, `CG-0003` and `CG-0007`. Rule E-2 orders the survivors by
`grantId` ascending: `CG-0001`, `CG-0003`, `CG-0007`. Rule B-3 trims the principal of
`CG-0007` to `ops.custodian@custodian-bank.example`. Rule B-1 lower-cases the `certId`
references carried on the grant objects. Rule A-1 orders the members of each grant
object as `certId`, `grantId`, `grantedEpoch`, `principal`. The correct `custodyGrants`
array therefore has exactly three elements, in the order `CG-0001`, `CG-0003`,
`CG-0007`, with `CG-0007`'s principal trimmed.

A pipeline exhibiting the revocation defect would carry all five grants, including the
revoked `CG-0002` and `CG-0005`. That output violates Rule C-1 and fails the
verification's revocation check (Section 11.2), which asserts both that the revoked
identifiers are absent and that the survivors are present. Note that the revoked grants
`CG-0002` and `CG-0005` reference certificates `WC-001` and `WC-002`; those
certificates still appear in `wrappingCertificates` (Section 7.4), and `WC-001` and
`WC-002` also still have surviving grants (`CG-0001` and `CG-0003` respectively), so in
this example no certificate is left grant-less. The certificate `WC-004` referenced by
the surviving `CG-0007` naturally also appears.

### A.4 Whitespace collapse in subjects versus trimming in principals

Consider a wrapping certificate whose subject is recorded as
`CN=Escrow Wrapping   Authority,  O=Custodian Bank,C=DE` — with three spaces inside the
common name and two spaces after the first comma. Rule B-2 collapses every run of
whitespace to a single space and trims the ends, yielding
`CN=Escrow Wrapping Authority, O=Custodian Bank,C=DE`. Note that the collapse does not
insert spaces where there are none: `O=Custodian Bank,C=DE` has no space after the
comma before `C=DE`, and the rule does not add one, because the rule collapses existing
whitespace runs and does not parse or reformat the distinguished name.

Contrast the principal `  ops.custodian@custodian-bank.example  ` of Section A.3. Rule
B-3 trims the surrounding whitespace to `ops.custodian@custodian-bank.example` but, were
there any internal whitespace, would leave it intact. The two rules differ precisely on
internal whitespace: subjects collapse it, principals preserve it. An implementer who
applies the same transformation to both fields has misread one of the two rules.

### A.5 Assembling the full top-level object

Putting the pieces together, the top-level manifest object for the accompanying export
has, in canonical (lexicographic) member order (Rule A-1, Section 5.2):

1. `custodyGrants` — the three surviving grants of Section A.3, ordered `CG-0001`,
   `CG-0003`, `CG-0007`.
2. `generatedFrom` — the export's `exportId`.
3. `keyVersions` — the three keys of Section A.2, ordered `kms-root-alpha`,
   `kms-root-mu`, `kms-root-zeta`, each with its folded versions in ascending version
   order (the `kms-root-zeta` entry as worked in Section A.1).
4. `manifestVersion` — the integer `1`.
5. `recordCount` — the total folded version count. In the accompanying export
   `kms-root-alpha` has two versions, `kms-root-mu` has one, and `kms-root-zeta` has
   three, so `recordCount` is six (Rule D-2).
6. `wrappingCertificates` — the certificates ordered by `certId`, with subjects
   collapsed and key references lower-cased.

The serialized bytes of this object, indented consistently and with members in the
order above at every level, are what the SHA-256 digest is computed over. Any deviation
— a member out of order, an un-normalized value, a dropped version, an included revoked
grant — changes the bytes and therefore the digest.

---

## Appendix B — Extended rationale for each rule

The body of this report states the rules; this appendix explains, at length, why each
rule is what it is. The rationale matters because an implementer who understands the
purpose of a rule is far less likely to introduce the kind of subtle defect the 2023
incident produced. Each of the three defects arose not from carelessness but from a
plausible-sounding misunderstanding of the purpose of the rule the defect violated, and
the antidote to a plausible-sounding misunderstanding is a clear statement of the
actual purpose.

### B.1 Why canonical ordering is a regulatory requirement and not a preference

It is tempting to regard the ordering of keys in a JSON object as a cosmetic matter.
The JSON data model treats objects as unordered maps; a parser that reads
`{"a":1,"b":2}` and one that reads `{"b":2,"a":1}` build the same in-memory object. If
the manifest were consumed only by parsing it back into a data structure, ordering
would indeed be cosmetic. But the manifest is not consumed only by parsing; it is
consumed by *hashing*, and a hash is computed over bytes, not over the abstract data
model. Two byte sequences that parse to the same object have different hashes if their
bytes differ, and their bytes differ if their keys are in different orders. The
regulator's independent-verification control (Section 2.1, SUP-CUST-7.3) depends on the
bank and the supervisor computing the same hash from the same archive; that is possible
only if both compute the same bytes, which is possible only if the byte layout is
canonical.

One might ask why the control is built on hashing bytes rather than on comparing parsed
structures. The answer is operational: the supervisor does not want to trust the bank's
parser, or its own, to be free of quirks that might mask a discrepancy; it wants a
mechanical, tool-independent check that any party can run with a standard hashing
utility on a file. A byte-level digest is exactly such a check. It is only as good as
the canonicalization, though — without canonicalization, two honest, correct
implementations produce different bytes and the check reports a false discrepancy. So
the canonicalization rule is what makes the byte-level check usable. It is a regulatory
requirement in the fullest sense: without it, the control specified by SUP-CUST-7.3 does
not function.

Given that some canonical order is required, why lexicographic order specifically?
Because lexicographic order is the one order that requires no shared convention beyond
"sort the keys," which every implementer can do identically without consulting a table.
A "logical" order — provenance first, then payload, then counts — would require the
bank and the supervisor to agree on and maintain a table of which key goes where, and
any drift in that table between the two parties reintroduces the discrepancy the
canonicalization was meant to remove. Lexicographic order is self-describing: given the
set of keys, the order is determined, with no table to maintain. This is why Section 5.2
is at pains to point out that the top-level order is the lexicographic order of the six
member names and *not* a logical grouping, even though a logical grouping might read
more naturally to a human. Readability is not the objective; reproducibility is.

The defect this rule corrects (the 2023-07-20 finding) is the most insidious of the
three because it produces a manifest that is *content-correct* — every grant, every
version, every value is right — and yet fails verification, because the bytes are in the
wrong order. An engineer debugging such a failure, having confirmed that the content is
correct, may be baffled that the digest still mismatches. The requirement is unambiguous:
the manifest's object members must appear in lexicographic key order at every level
(Section 5), regardless of the order in which the building code produces them. A manifest
that is content-correct but not canonically ordered fails verification, because the
arrangement changes every object in the manifest and therefore the whole digest.

### B.2 Why value normalization is separate from ordering

Ordering and normalization both serve reproducibility, but they address different
sources of byte-level variation. Ordering addresses variation in the *arrangement* of
members; normalization addresses variation in the *content* of values. An implementer
might satisfy one and not the other: a manifest with canonically ordered keys but
un-normalized values (a mixed-case key identifier, an uncollapsed subject) has the
right arrangement but the wrong bytes, and its digest mismatches. So normalization is
not subsumed by ordering; it is an independent requirement.

The specific normalizations are chosen to erase exactly the incidental variations the
archive is known to contain, and no others. The archive records key identifiers with
mixed case because different generations of the key-management platform capitalized
them differently; the identifier `KMS-ROOT-Alpha` and a hypothetical `kms-root-alpha`
denote the same key, and the manifest must treat them identically, so it lower-cases
(Rule B-1). The archive records algorithm names in lower case as a convention of the
cryptographic library that produced them, but the regulator's own catalogues record
them in upper case, so the manifest upper-cases to match the regulator's catalogue
(Rule B-4). The archive records certificate subjects with inconsistent internal spacing
because different certificate authorities formatted their distinguished names
differently, and the spacing carries no meaning, so the manifest collapses it (Rule
B-2). The archive sometimes records principals with incidental leading or trailing
whitespace introduced by copy-and-paste during grant issuance, and that whitespace
carries no meaning, so the manifest trims it (Rule B-3).

Crucially, the normalizations stop there. The manifest does not lower-case principals
(a principal's case may be significant — an email local-part is conventionally
case-insensitive but the archive does not guarantee it, so the manifest does not assume
it). The manifest does not collapse internal whitespace in principals (a principal is an
opaque identity string). The manifest does not reformat distinguished names beyond
collapsing whitespace (it does not, for instance, normalize the order of the
distinguished-name components, because that would require parsing the DN and the rule
deliberately does not). Each normalization is the minimum transformation that erases a
known incidental variation, and the rules are explicit about their limits precisely so
that an implementer does not over-normalize. Over-normalization is as much a defect as
under-normalization: a manifest that lower-cases a principal whose case was significant
has corrupted the data, and its digest will mismatch the reference just as surely as one
that failed to trim.

### B.3 Why revoked grants are excluded rather than annotated

Section 7.2 records that an earlier draft proposed annotating revoked grants rather than
excluding them, and that the supervisor rejected the annotation approach. The rationale
deserves fuller treatment because the annotation approach is superficially attractive
and an implementer who finds it attractive might be tempted to "improve" the manifest by
reintroducing it.

The attraction of annotation is that it appears to lose no information: a manifest that
lists every grant, marking the revoked ones, contains strictly more than a manifest that
omits the revoked ones, and "more information" sounds better than "less." But the
manifest is not a general-purpose data dump; it is a *statement of current custody*, an
answer to a specific question — "who holds custody now?" — and the correct answer to
that question does not include revoked grantees. A revoked grantee holds no custody. To
list them, even with a marker, is to answer a different question ("who has ever held
custody?") whose answer belongs in the archive, not in the current-custody statement. The
supervisor's concern, grounded in SUP-CUST-11, is that a reader of the current-custody
statement who sees a revoked grantee listed — however marked — may miscount the
population with access, may cite the revoked grantee in a downstream analysis, or may
simply be confused about whether the revocation took effect. The clean way to prevent all
of these is for the current-custody statement to contain only current custody: exclusion,
not annotation.

There is also a reproducibility dimension. If revoked grants were annotated rather than
excluded, the annotation would become part of the canonical bytes, and the exact form of
the annotation — the marker key, its value, its position — would have to be specified and
agreed, adding surface area to the canonicalization. Exclusion removes that surface area:
a revoked grant simply is not there, and there is nothing about its representation to
specify. Simplicity in the canonical form is itself a virtue, because every additional
specified detail is an additional opportunity for two implementations to diverge.

The defect this rule corrects (the 2023-04-02 finding) arose from a pipeline that copied
every grant from the export into the statement without consulting the revocation flag.
The mistake is easy to make because copying every grant is the obvious first
implementation, and the revocation flag is easy to overlook when it is one boolean among
several fields. The requirement is unambiguous: a grant must appear in the manifest only
when its revocation flag is false (Section 7), and the exclusion applies before the grants
are ordered and serialized. Carrying a revoked grant into the manifest misstates the
bank's control posture to its regulator.

### B.4 Why every version is folded rather than only the latest

The version-folding rule (Section 8) is the rule most likely to be misunderstood by an
engineer who has not internalized the purpose of the escrow programme. The intuition
"only the current version matters" is correct for many systems — a password store cares
only about the current password hash, a configuration system cares only about the current
configuration — but it is exactly wrong for an escrow archive, whose entire reason for
existing is to keep *historical* material recoverable. A client's records from 2019 were
encrypted with the key version that was active in 2019; recovering those records requires
that specific version, which by 2024 is a retired historical version. If the migration
dropped retired versions, the archive would silently lose the ability to recover the very
records it exists to protect, and the loss would not be detected until someone needed a
2019 record and found the key gone.

The custody manifest reflects this: it is a statement of *all* the key material the bank
holds, because the bank's custody obligation extends to all of it, active and retired
alike. A manifest that listed only active versions would understate the bank's holdings
and, under REC-AUDIT-4, would break the audit trail that lets the supervisor verify that
historical material remains accounted for. So Rule D-1 requires the complete fold: every
version, no exceptions, ordered from earliest to latest.

The defect this rule corrects (the 2023-05-11 finding) arose from a version-reconciliation
step that, faced with multiple versions of a key, kept the one with the highest version
number and discarded the rest — a natural implementation of "reconcile to the current
version" that is correct for systems where only the current state matters and catastrophic
for an escrow archive where all states matter. The requirement is unambiguous: every
version of every key must be folded into the manifest in ascending version order (Rule
D-1), not reduced to the latest. The record count (Rule D-2) is the sum of the folded
versions across all keys, so a pipeline that keeps only the latest version also reports a
record count equal to the number of keys (one surviving version each) rather than the true
total, which is a second, visible symptom of the same defect.

### B.5 Why the orderings are what they are

The four array orderings (Section 9) are each chosen so that the ordering key is present,
total, and stable. "Present" means every element has the key (every key has a normalized
identifier, every grant a grant identifier, every certificate a certificate identifier,
every version an integer version). "Total" means the key induces a total order (no two
distinct elements compare equal — identifiers are unique, and within a key the version
integers are unique). "Stable" means the order does not depend on anything outside the
element itself (it does not depend on the element's position in the export, which is not
guaranteed). These three properties together ensure that the ordering is deterministic and
reproducible, which is the same objective the canonical key ordering serves for object
members.

The choice of the normalized key identifier (rather than the raw one) as the ordering key
for `keyVersions` is the subtlest of the four and is treated at length in Section A.2: the
raw and normalized orders can differ, and the rule requires the normalized order because
the manifest presents normalized identifiers and it would be incoherent to present them in
an order determined by their un-presented raw form. The choice of integer comparison
(rather than string comparison) for versions is the second subtlety: version numbers are
integers and must be compared as integers, so that version 2 precedes version 10; a string
comparison would place `10` before `2`, which is wrong. The zero-padding of grant and
certificate identifiers is what lets those two use ordinary string comparison and still get
numeric order, and Section 9.2 and 9.3 note that the pipeline may rely on the padding rather
than parse the numeric suffix.

---

## Appendix C — Edge cases and frequently asked questions

This appendix collects the questions the custody engineering team raised during
implementation, together with the answers the supervisory reviewer gave. Each is
phrased as a question and answered normatively; where an answer restates a rule from the
body, the rule reference is given so the reader can confirm the answer is not a new
requirement but an application of an existing one.

### C.1 What if a key has exactly one version?

A key with a single version is folded exactly as a key with many: its `versions` array
has one element, that element is the single version, and it is ordered trivially (Rule
E-4 on a one-element list is a no-op). The single-version case is not special; it is the
degenerate case of the general fold, and an implementer should not write a special path
for it. In the accompanying export the key `kms-root-mu` has exactly one version, and its
manifest entry has a one-element `versions` array. A pipeline exhibiting the version-
folding defect would produce the *same* one-element array for this key as a correct
pipeline, because dropping all-but-the-latest of a single-version key drops nothing;
this is why a verification that only ever looked at single-version keys would fail to
detect the folding defect, and why the verification (Section 11.2) exercises a
multi-version key.

### C.2 What if two keys normalize to the same identifier?

The archive guarantees that key identifiers are unique after normalization; two entries
that normalize to the same identifier would be the same key and would not appear as two
entries. If such a collision were nonetheless present in an export, it would indicate
archive corruption, and the correct response is to halt the migration and report the
corruption to the supervisor, not to attempt to merge or arbitrate between the colliding
entries. The pipeline is a faithful transformer of a well-formed archive, not a repair
tool for a malformed one. The accompanying export contains no such collision.

### C.3 What if a grant references a certificate that is not in the export?

A grant whose `certId` does not match any certificate in the export is a dangling
reference. Like a normalized-identifier collision (Section C.2), a dangling reference
indicates archive inconsistency. The revocation rule (Section 7) still applies to the
grant on its own terms — a revoked dangling grant is excluded, a non-revoked dangling
grant survives — because the revocation decision consults only the grant's own flag
(Rule C-1, Section 7.3) and does not depend on the referenced certificate existing. The
manifest does not attempt to prune grants whose certificates are missing; it carries the
surviving grant with its (dangling) reference, and the inconsistency surfaces to the
supervisor at reconciliation. The accompanying export contains no dangling references.

### C.4 Does an expired certificate remove its grants?

No. Certificate expiry (`notAfterEpoch` in the past) is independent of grant survival.
A grant survives if and only if it is not revoked (Rule C-1); the expiry of the
certificate it references does not revoke it. Section 7.3 is explicit that the pipeline
MUST NOT infer revocation from certificate expiry. A grant against an expired
certificate is carried into the manifest if it is not revoked, and the certificate is
carried into `wrappingCertificates` regardless of its expiry. The manifest records the
expiry (via `notAfterEpoch`) so that a reader can see that the certificate has expired,
but the pipeline does not act on the expiry.

### C.5 Is the `jurisdiction` field carried into the manifest?

No. Section 4.1 lists `jurisdiction` as export metadata that is not carried into the
manifest. The manifest's provenance is captured by `generatedFrom` (the `exportId`) and
`manifestVersion`; the jurisdiction is bookkeeping for the archive and does not appear in
the manifest's six top-level members (Section 5.2). An implementer who adds a
`jurisdiction` member to the manifest has added a seventh top-level member not in the
specification, which changes the canonical bytes and fails verification.

### C.6 What is the exact form of `recordCount` when there are no keys?

An export with no escrowed keys produces a manifest whose `keyVersions` array is empty
and whose `recordCount` is zero (the sum over an empty set of keys is zero, Rule D-2).
This is a legitimate, if unusual, manifest. The accompanying export has three keys and a
`recordCount` of six.

### C.7 Are the epoch fields normalized or reformatted?

No. The epoch fields — `createdEpoch`, `notAfterEpoch`, `grantedEpoch` — are integers
carried verbatim (Section 6.1). The pipeline does not convert them to date strings,
does not change their units, and does not round them. They are seconds since the Unix
epoch as recorded in the archive, and they appear in the manifest as the same integers.
An implementer who reformats an epoch as an ISO-8601 date string has changed the value
and the bytes, and verification fails.

### C.8 Does the manifest include the custody status of a version?

Yes. Each folded version object carries `custodyStatus` verbatim (Section 4.5, Section
6.1). The custody status does not affect whether the version is folded (Section 8.5) —
every version is folded regardless of status — but the status is carried so the reader
can see each version's lifecycle state. The canonical member order within a version
object places `custodyStatus` after `createdEpoch` and before `version` (Rule A-1,
Section 5.3), which is its lexicographic position.

### C.9 What if the export's arrays are already in canonical order?

Then the pipeline's ordering step is a no-op for those arrays, and the output is the
same as it would be for an unordered export. The pipeline MUST apply the ordering
regardless (it MUST NOT skip ordering on the assumption that the export is already
ordered), because the export makes no ordering guarantee (Section 4.1) and an export
that happens to be ordered is not distinguishable, by contract, from one that is not.
Applying a sort to an already-sorted array is correct and cheap; skipping the sort on an
unordered array is incorrect. The pipeline always sorts.

### C.10 Should the manifest be pretty-printed or minified?

The manifest is serialized with consistent indentation (pretty-printed) so that a human
reviewer can read it. The indentation is part of the canonical bytes: the reference
implementation and the supervisor's implementation both pretty-print with the same
indentation, so the pretty-printed bytes are reproducible. An implementer must match the
reference's serialization style; the accompanying reference implementation enables
indented output. What matters for reproducibility is that both parties serialize the same
way, and the agreed way is indented output with sorted keys.

### C.11 If I fix only one of the three defects, will verification pass?

No. Section 11.2 exercises each rule set independently, and Section 3 emphasizes that the
three defects are independent. Fixing the revocation defect while leaving the folding
defect in place produces a manifest that excludes revoked grants but drops historical
versions; the version-folding check fails. Fixing the folding defect while leaving the
ordering defect in place produces a content-correct manifest whose bytes are in the wrong
order; the canonical-ordering check fails and the digest mismatches. Only fixing all
three produces a manifest that passes every check and matches the expected digest. There
is no partial credit at the digest level: the digest matches only when the manifest is
byte-identical to the reference, which requires all three defects to be absent and all
normalizations to be applied.

### C.12 Where does the manifest get written, and relative to what?

The manifest is written to `custody_manifest.json` in the service's working directory,
and the export and this report are read from `assets/` and `docs/` relative to the same
working directory (Section 0, Section 10). The service must therefore be run from the
project root so that these relative paths resolve. Running it from a different directory
would cause the export read to fail (the export would not be found at
`assets/key-escrow-export.json` relative to the wrong directory) and the migration would
error before producing a manifest.

---

## Appendix D — Correspondence log with the supervisor

This appendix reproduces, in summarized form, the material exchanges between the bank's
custody engineering team and the supervisory reviewer during the period the rules of
this report were settled. It is included because the reasoning that produced each rule is
often clearest in the exchange that produced it, and because the log records which
alternatives were considered and rejected, so that an implementer does not waste effort
reconsidering a settled question. The log is chronological; each entry gives the date,
the party that initiated the exchange, and a summary of its content and outcome.

**2023-04-02 — Supervisor to bank.** The supervisor reported the first reconciliation
discrepancy: the filed custody statement listed principals whose grants the bank's own
records showed as revoked. The supervisor asked the bank to explain how a revoked grant
came to appear in a statement of current custody. The bank's initial response, filed
2023-04-05, was that the statement was generated by copying the archive's grant
collection wholesale, and that the copy did not consult the revocation flag. The
supervisor characterized this as a control deficiency and asked for a remediation plan.

**2023-04-11 — Bank to supervisor.** The bank proposed to remediate the revocation
discrepancy by adding a `revoked` marker to each grant in the statement, so that a reader
could distinguish revoked from active grants while retaining the full grant history in
the statement. The bank argued that this "lost no information." The supervisor, in a
reply dated 2023-04-18, rejected the proposal on the grounds set out in Section 7.2 and
Section B.3: a statement of *current* custody must not list principals who hold no
current custody, even with a marker, because SUP-CUST-11 requires that withdrawals be
reflected completely, and a marked-but-listed revoked grantee is not a complete
reflection of the withdrawal. The supervisor directed that revoked grants be *excluded*
from the statement, with the revocation history retained in the archive and in the
working migration tables (which became the rule in Section 10.3) rather than in the
statement itself.

**2023-04-25 — Bank to supervisor.** The bank accepted the exclusion approach and asked
a clarifying question: if excluding a grant leaves a certificate with no surviving grant,
should the certificate also be excluded? The supervisor replied 2023-04-28 that it should
not: certificates are carried on their own terms, independently of whether any grant
against them survives, because the certificate's existence and expiry are facts the
statement records regardless of grants (the rule in Section 7.4). A certificate with no
surviving grant is a legitimate, if noteworthy, entry.

**2023-05-11 — Supervisor to bank.** The supervisor reported the second reconciliation
discrepancy: the filed statement listed, for each key, only the most recent version, and
omitted historical versions. The supervisor observed that this broke the audit trail
required by REC-AUDIT-4 and understated the material the bank held. The bank's response,
filed 2023-05-16, was that the statement's version-reconciliation step kept the highest-
numbered version of each key and discarded the rest, on the assumption that only the
active version was of interest.

**2023-05-23 — Supervisor to bank.** The supervisor rejected the "only the active
version" assumption at length, on the grounds set out in Section 8.2 and Section B.4: the
escrow programme exists to keep historical records recoverable, historical records are
recoverable only with the historical key versions that protected them, and a statement
that omits historical versions therefore understates the bank's recoverable holdings and
breaks the audit trail. The supervisor directed that *every* version be folded into the
statement (the rule in Section 8.1), in ascending version order (the rule in Section
9.4), and that the record count reflect the total folded versions rather than the number
of keys (the rule in Section 8.4).

**2023-06-02 — Bank to supervisor.** The bank asked whether versions should be
deduplicated when two versions of a key shared an algorithm and custody status. The
supervisor replied 2023-06-07 that they should not: each version is a distinct rotation
distinguished by its version integer, and two versions that happen to share other
attributes are still two versions protecting two (possibly overlapping) sets of records
(the rule in Section 8.3). Deduplication would drop a version and reintroduce the very
defect under remediation.

**2023-07-20 — Supervisor to bank.** After the revocation and version discrepancies were
understood but before the pipeline was corrected, the supervisor attempted an independent
recomputation of the statement's digest and found that it disagreed with the bank's even
on inputs where the content was agreed. Investigation showed that the two implementations
serialized the statement's JSON objects with different member orderings: the bank's
pipeline emitted members in the order its building code populated them, and the
supervisor's reference implementation emitted them in lexicographic order. The supervisor
noted that under SUP-CUST-7.3 the byte-level reproducibility of the statement was itself a
requirement, and that an inconsistent member ordering defeated it.

**2023-08-01 — Bank to supervisor.** The bank proposed to fix the ordering discrepancy by
agreeing a fixed "logical" member order (provenance, then payload arrays, then counts) and
documenting it in a table both parties would maintain. The supervisor, replying 2023-08-08,
rejected the table-based approach on the grounds set out in Section 5.2 and Section B.1: a
maintained table is a source of drift between the two parties, whereas lexicographic order
is self-describing and requires no maintained table. The supervisor directed that member
order be lexicographic ascending at every object level (the rule in Section 5), as a property
of the serialized output rather than of the order in which the building code produces members.

**2023-08-15 — Bank to supervisor.** The bank asked a series of normalization questions
that the supervisor answered over the following weeks, producing the rules of Section 6:
key identifiers lower-cased (2023-08-22), algorithms upper-cased (2023-08-29), certificate
subjects whitespace-collapsed (2023-09-05), and principals trimmed but not internally
collapsed (2023-09-12). The supervisor emphasized in each reply that the normalization
should be the minimum that erases a known incidental variation, and specifically warned
against over-normalizing principals (Section B.2), because a principal's internal
characters are significant.

**2023-09-01 — Bank to supervisor.** The bank gave the written undertaking (Section 2.2)
to replay the entire archive through a corrected pipeline, produce a verifiable canonical
manifest, and document the rules. The supervisor acknowledged the undertaking 2023-09-06
and asked additionally that the pipeline not embed the expected digest as a constant, so
that the replay genuinely recomputes the manifest (the requirement in Section 2.2). The
bank confirmed 2023-09-13 that its pipeline would derive the manifest from the export and
the rules with no embedded digest.

**2023-09-19 — Bank to supervisor.** The bank asked whether the ordering of `keyVersions`
should be by the raw or the normalized key identifier, having discovered (Section A.2)
that the two can differ. The supervisor replied 2023-09-26 that it must be by the
normalized identifier, because the manifest presents normalized identifiers and the
presented order should match the presented form (the rule in Section 9.1), and directed
that normalization precede the sort (the sequencing in Section 6.6).

**2023-10-03 — Bank to supervisor.** The bank asked whether version ordering should be by
string or integer comparison of the version field. The supervisor replied 2023-10-10 that
it must be integer comparison, so that version 2 precedes version 10 (the rule in Section
9.4), and noted that grant and certificate identifiers, being zero-padded, could use string
comparison and still sort numerically (the rules in Section 9.2 and 9.3).

**2023-10-17 through 2024-02-28 — Iterative drafting.** Over this period the bank and the
supervisor exchanged successive drafts of the rules, converging on the wording in Sections
5 through 9. The substantive content did not change after the decisions logged above; the
exchanges refined wording, added the worked examples of Appendix A, and confirmed the edge
cases of Appendix C. Drafts KE-REM-2024-0392 and KE-REM-2024-0405 date from this period and
are superseded by this final report.

**2024-03-14 — Supervisor to bank.** The supervisor confirmed that the reference
implementation, run on the sample export, produced a manifest whose digest the supervisor
recorded as the expected checksum, and directed that the checksum artifact distributed with
the export carry that value once regenerated from the reference solution. The supervisor
reiterated that the value must come from the reference implementation's output and must not
be embedded in the pipeline under test.

**2024-04-17 — Report finalized.** This report was finalized and the supervised replay
scheduled. The three defects — revocation, version folding, and canonical ordering — were
recorded as the defects the corrected pipeline must not exhibit, and the verification of
Section 11 was adopted as the acceptance criterion.

---

## Appendix E — Implementation guidance

This appendix is advisory rather than normative: it describes an implementation approach
that satisfies the rules, without mandating that approach. An implementation that meets
the rules of Sections 5 through 9 is conforming regardless of whether it follows the
structure suggested here. The guidance is offered because the 2023 defects were, in each
case, the result of a structural choice that made the defect easy to introduce, and a
structure that makes the defects hard to introduce is worth describing.

### E.1 A pipeline in five stages

The recommended structure decomposes the replay into five stages, corresponding to the
five steps of Section 6.6: parse, normalize, select-and-fold, order, serialize. Each stage
is a pure transformation of the data produced by the previous stage, which makes each stage
independently testable and makes the dependencies between stages explicit.

The *parse* stage reads the export from `assets/key-escrow-export.json` and deserializes it
into an in-memory object graph mirroring the data model of Section 4. It performs no
transformation beyond deserialization; in particular it does not sort, filter, or
normalize. Keeping the parse stage transformation-free means the object graph faithfully
reflects the export, including its arbitrary ordering and its revoked grants, which is the
correct input to the later stages.

The *normalize* stage applies the value normalizations of Section 6: lower-casing key
identifiers, upper-casing algorithms, collapsing certificate-subject whitespace, and
trimming principals. It is placed before selection and ordering because the ordering of
`keyVersions` depends on the normalized key identifier (Section 6.6, Section 9.1). A common
structural mistake is to fold normalization into the serialization stage, applying it
field-by-field as each value is written; this works for fields that are not sort keys but
fails for the key identifier, whose normalized form is needed before the sort. Keeping
normalization as its own stage, applied to the whole graph before ordering, avoids the
mistake by construction.

The *select-and-fold* stage applies the two selection rules: it excludes revoked grants
(Section 7) and it folds every version of every key (Section 8). The exclusion is a filter
on the grant collection retaining only grants whose revocation flag is false. The fold is a
gather of each key's complete version list. Isolating both selection rules in a single,
clearly-named stage makes the manifest's contents easy to review: a reviewer looking at
this stage can see directly whether revoked grants are excluded and whether every version
is folded.

The *order* stage applies the four array orderings of Section 9: keys by normalized
identifier, surviving grants by grant identifier, certificates by certificate identifier,
and versions within each key by integer version. It operates on the selected-and-folded
data, so it sorts the survivors (not all grants) and sorts the folded versions (all of
them). Placing ordering after selection means the sort operates on exactly the elements
that will appear in the manifest.

The *serialize* stage builds the manifest object and writes it with canonical key ordering
(Section 5). Every object in the manifest must have its members emitted in lexicographic
key order, applied uniformly to every object and independently of the order in which the
building code populates them (Section 5.2).

### E.2 Which stage owns each requirement

The five-stage structure localizes each requirement to a single stage, which is the
structure's main benefit for this remediation:

- *Canonical ordering* is owned by the serialize stage: every object's members must be
  emitted in lexicographic key order, uniformly and independently of the order in which the
  building code populates them. The building code's order is not the canonical order in
  general and, even where it coincides at some levels, does not coincide at all levels, so
  canonical order is a property of the serialized output, not of the building code.
- *Revocation exclusion* is owned by the select-and-fold stage: the grant collection
  reaching the order and serialize stages must contain only grants whose revocation flag is
  false. A reviewer confirms this by inspecting the grant collection at that boundary.
- *Version folding* is owned by the select-and-fold stage: every version of every key must
  reach the manifest, not only the latest. A reviewer confirms this by checking that the
  stage carries the full version list.

An implementer who follows the five-stage structure and gets each of these three points
right produces a conforming manifest. An implementer who uses a different structure must
still get the three points right, but in a different structure they may not be as cleanly
localized, which is why the structured approach is recommended.

### E.3 On not embedding the expected digest

Section 2.2 forbids embedding the expected digest in the pipeline. The five-stage structure
supports this naturally: no stage consults an expected digest, because each stage is a
transformation of the export data and the rules, and none of them knows or needs the
answer. An implementer should be suspicious of any code that references a fixed digest
value, a fixed manifest string, or a special case for the sample export; all three are ways
of asserting the answer rather than computing it, and all three are forbidden. The manifest
must be reproducible from the export and the rules by any conforming implementation, which
is possible only if the implementation actually applies the rules rather than reproducing a
stored result.

### E.4 On the migration tables

The migration tables (Section 10) are written by a stage parallel to the manifest
serialization: the same selected-and-normalized data that feeds the manifest also feeds the
table writes, with the difference that the `custody_grants` table receives *all* grants,
including revoked ones (Section 10.3), whereas the manifest receives only survivors. An
implementer should take care that the "all grants" write to the table and the "surviving
grants" write to the manifest draw from the correct collections; a structural mistake that
writes only survivors to the table, or all grants to the manifest, confuses the working
mirror with the current-custody statement. The `key_versions` table receives every folded
version, consistent with the manifest and with Rule D-1, so the table's row count equals the
manifest's record count.

---

## Appendix F — Verification test design

This appendix describes the design of the verification suite of Section 11.2 in enough
detail that an implementer can anticipate what the suite checks and can self-check their
implementation before the supervised replay. The suite is designed so that each of the
three 2023 defects, if present, causes at least one specific check to fail, and so that a
conforming manifest passes every check.

### F.1 The canonical-ordering check

This check parses the produced manifest and inspects the order in which object members
appear in the parsed representation, which reflects the order they appeared in the
serialized bytes. It asserts that the top-level members appear in lexicographic ascending
order — `custodyGrants`, `generatedFrom`, `keyVersions`, `manifestVersion`, `recordCount`,
`wrappingCertificates` — and that a representative nested object (for example the first
grant) also has its members in lexicographic order. A pipeline exhibiting the ordering
defect emits the top-level members in the order the building code populated them, which is
not lexicographic, and the check fails on the top-level assertion. The check is designed to
catch the defect at the top level because the top-level order is where insertion-order and
lexicographic-order most visibly diverge; a pipeline that happens to populate some nested
object in lexicographic order still fails the top-level assertion if its top-level order is
insertion order.

### F.2 The revocation check

This check parses the produced manifest, collects the grant identifiers present in
`custodyGrants`, and asserts two things: that no identifier belonging to a revoked grant in
the export appears, and that every identifier belonging to a non-revoked grant appears. On
the sample export the revoked grants are `CG-0002` and `CG-0005`, so the check asserts their
absence, and the surviving grants are `CG-0001`, `CG-0003`, `CG-0007`, so the check asserts
their presence and asserts that the surviving set has exactly three members. A pipeline
exhibiting the revocation defect carries `CG-0002` and `CG-0005` into the manifest, and the
absence assertion fails. The check deliberately asserts both absence of the revoked and
presence of the survivors, so that a pipeline that over-corrects (dropping a non-revoked
grant) also fails, not only one that under-corrects.

### F.3 The version-folding check

This check parses the produced manifest, locates a key known to have multiple versions in
the export (on the sample export, `kms-root-zeta`, which has three), and asserts that all of
its versions appear, in ascending version order. It also asserts that the top-level
`recordCount` equals the total folded version count across all keys (six on the sample
export). A pipeline exhibiting the version-folding defect produces a one-element `versions`
array for `kms-root-zeta` (only the latest version) and a `recordCount` equal to the number
of keys (three) rather than the total versions (six); both the per-key assertion and the
record-count assertion fail. The check uses a multi-version key deliberately, because a
single-version key does not distinguish the defect (Section C.1).

### F.4 The endpoint checks

Beyond the three rule checks, the suite exercises the HTTP surface: it asserts that the
health probe responds and that the replay endpoint returns success, emits the manifest file,
and reports the record count. These checks confirm that the service is wired correctly and
that the replay path runs end to end; they are not rule checks, but a pipeline that fails to
emit a manifest, or that errors on replay, fails them. The replay endpoint's reported record
count is asserted to be six on the sample export, which ties the endpoint check to the
version-folding rule: a pipeline that drops versions reports the wrong count here too.

### F.5 Self-checking before the supervised replay

An implementer can run the verification suite against their own manifest before the
supervised replay. A conforming implementation passes every check; a non-conforming one
fails the check corresponding to each defect it exhibits. Because the checks are independent,
the pattern of failures points at the specific defects: a failing ordering check indicates a
serializer left in insertion-order mode; a failing revocation check indicates a missing
grant filter; a failing version-folding check indicates a reduce-to-latest fold. An
implementer should not proceed to the supervised replay until every check passes, because a
supervised replay that fails is a reportable event.

---

## Appendix G — Detailed incident investigation

This appendix expands the timeline of Section 3 into a fuller investigative narrative, so
that the reader understands not only what the three defects were but how they were
discovered, why they had gone unnoticed, and what the investigation concluded about their
root causes. The narrative is included because the supervisor required, as part of the
undertaking, that the bank document the investigation to a standard that would let an
independent party understand how the defects arose and be satisfied that the remediation
addresses their causes and not merely their symptoms.

### G.1 Discovery of the revocation defect

The revocation defect was discovered during the routine quarterly reconciliation of
2023-04-02. The reconciliation procedure has the supervisor draw the archive export and the
bank's filed custody statement, recompute the statement from the export, and compare. On
this occasion the recomputation, which correctly excluded revoked grants, listed fewer
principals than the bank's filed statement. The difference was traced to two grants,
belonging to a departed custodian and a decommissioned service identity, both of which the
archive recorded as revoked and both of which the bank's statement listed as though in
force.

The investigation into how the revoked grants came to be listed examined the code path that
generated the statement. The path read the archive's grant collection and, for each grant,
emitted a corresponding entry in the statement. The emission was unconditional: it did not
examine the grant's revocation flag. The flag was present in the data — the archive recorded
it faithfully — but the statement-generation code simply did not read it. The root cause was
identified as an incomplete implementation of the statement's selection logic: the developer
who wrote the emission loop implemented the "emit each grant" behaviour and did not implement
the "unless revoked" qualification, either because the qualification was not in the
specification the developer worked from or because it was overlooked.

The defect had gone unnoticed because, for most of the archive's history, the population of
revoked grants was small and the statement's readers did not cross-check it against the
archive's revocation records. It surfaced only when the quarterly reconciliation, which does
cross-check, happened to run at a time when two recently-revoked grants were present. The
investigation concluded that the reconciliation control was working as intended — it caught
the defect — but that the statement-generation code had a latent selection error that the
remediation must fix at the source, which it does via the revocation-exclusion rule of
Section 7 and the corresponding filter in the select-and-fold stage of the pipeline
(Appendix E).

### G.2 Discovery of the version-folding defect

The version-folding defect was discovered on 2023-05-11 when the supervisor, following up on
the revocation finding, examined the statement's treatment of key versions and observed that
each key was represented by a single version — always the most recent — even for keys the
archive recorded as having several. The supervisor recognized immediately that this broke the
audit trail: a statement that lists only the current version of each key cannot support an
audit of the bank's ability to recover records protected by earlier versions, which is the
core of what REC-AUDIT-4 requires the statement to support.

The investigation into the version-folding defect examined the statement's version-
reconciliation step. The step existed because the archive stores each key's versions as a
collection, and the statement author had to decide how to represent that collection. The
author chose to reduce the collection to a single representative version, selecting the one
with the highest version number on the reasoning that it was the "current" version and
therefore the one of interest. The reduction dropped every earlier version. The root cause
was identified as a misunderstanding of the purpose of the statement with respect to
versions: the author reasoned as though the statement were a statement of current state,
whereas the escrow programme requires a statement of all held material including historical
material. The reduce-to-latest choice is correct for a current-state statement and wrong for
an all-material statement, and the statement is an all-material statement.

The defect had gone unnoticed for the same structural reason as the revocation defect: the
statement's readers, prior to the reconciliation, did not cross-check the statement's version
coverage against the archive's version records. It surfaced when the supervisor, prompted by
the first finding, examined version coverage directly. The investigation concluded that the
remediation must require the complete version history rather than only the latest version,
which it does via the version-folding rule of Section 8, realized in the select-and-fold
stage of the pipeline (Appendix E).

### G.3 Discovery of the canonicalization defect

The canonicalization defect was discovered on 2023-07-20, later than the other two, because
it does not manifest as a content discrepancy — the content of the statement was correct with
respect to the members it contained — but as a byte-level discrepancy that only appears when
two implementations serialize the same content. It surfaced when the supervisor, in the
course of building an independent reference implementation to recompute statement digests,
found that its digest disagreed with the bank's even on content both parties agreed was
correct.

The investigation into the canonicalization defect compared the bytes the two implementations
produced for the same content. The bytes differed in the order of object members: the bank's
implementation emitted members in the order its building code populated them, and the
reference implementation emitted them in lexicographic order. Neither order was "wrong" as
JSON — both parse to the same object — but they produced different bytes and therefore
different digests, and the reproducibility requirement of SUP-CUST-7.3 requires the same
bytes. The root cause was identified as the absence of a canonicalization step: the bank's
serializer was left in its default mode, which preserves insertion order, and no step imposed
a canonical member order.

The defect had gone unnoticed because the bank's own tooling only ever compared statement
content, never statement bytes, so an ordering difference that preserved content was invisible
to it. It surfaced only when the supervisor introduced a byte-level digest comparison, which
is exactly the comparison the reproducibility control depends on. The investigation concluded
that the remediation must impose a canonical member order, which it does via the canonical-key-
ordering rule of Section 5, realized in the serialize stage of the pipeline (Appendix E).

### G.4 Common root cause and the remediation's response

Although the three defects have distinct proximate causes — revoked grants carried through,
each key reduced to its latest version, and members emitted in insertion order — the
investigation identified a common underlying theme:
each defect arose from an implementation choice that was reasonable for some *other* kind of
system but wrong for a reproducible, all-material, current-custody statement of a regulated
escrow archive. Copying every grant is reasonable for a general data dump but wrong for a
current-custody statement (which excludes revoked grants). Reducing to the latest version is
reasonable for a current-state view but wrong for an all-material statement (which folds all
versions). Preserving insertion order is reasonable for a human-read document but wrong for a
byte-reproducible one (which canonicalizes order). The remediation's response is to state, in
this report, precisely what kind of document the statement is and precisely what rules follow
from that, so that an implementer is not left to infer the document's nature from its name and
to guess at the rules. The rules of Sections 5 through 9 are the explicit statement the
investigation concluded was missing.

---

## Appendix H — Regulatory background

This appendix expands the regulatory citations of Section 2.1 into a fuller account of the
supervisory expectations the escrow programme operates under, so that an implementer
understands the force behind the rules and does not treat them as arbitrary. The account is a
summary of the applicable supervisory statements as they bear on the custody manifest; it is
not a substitute for the statements themselves, which govern in case of any discrepancy.

### H.1 SUP-CUST-7 and the accuracy of custody statements

SUP-CUST-7, the Supervisory Statement on Custody of Cryptographic Key Material, establishes
the baseline expectation that a custodian of cryptographic key material be able to produce, on
demand, an accurate statement of the custody posture of every piece of material it holds. The
statement's purpose is to let the supervisor verify, at any time, that the custodian's control
over the material is what the custodian represents it to be. Accuracy in this context has two
dimensions: the statement must include everything the custodian holds (completeness) and must
represent each holding correctly (fidelity). The version-folding rule (Section 8) serves
completeness with respect to versions — every version held is in the statement — and the
normalization rules (Section 6) serve fidelity — each value is represented in its canonical
form. A statement that dropped versions would be incomplete; a statement that misrepresented
values would be low-fidelity; SUP-CUST-7 requires neither.

### H.2 SUP-CUST-7.3 and reproducibility

SUP-CUST-7.3 sharpens the accuracy expectation of SUP-CUST-7 with a reproducibility
requirement: two parties computing the statement from the same underlying archive must arrive
at byte-identical results. The rationale is a verification one. The supervisor does not merely
receive the custodian's statement and trust it; it recomputes the statement independently from
the archive and compares. For the comparison to be meaningful, the two computations must, on
identical input and identical rules, produce identical output — otherwise a discrepancy might
reflect a difference in computation rather than a difference in the underlying custody posture.
Byte-level reproducibility guarantees that any discrepancy reflects a real difference, which is
what makes the recompute-and-compare control sound. The canonical-ordering rule (Section 5) and
the normalization rules (Section 6) exist to satisfy SUP-CUST-7.3: together they fix the
statement's byte layout so that identical input and rules produce identical bytes.

### H.3 SUP-CUST-11 and the completeness of revocation

SUP-CUST-11, the Supervisory Statement on Revocation and Withdrawal of Access, requires that
when an authorization is withdrawn, the withdrawal be reflected promptly and completely in any
statement of current access. The word "completely" is the operative one for the custody
manifest: a withdrawal is reflected completely only if the withdrawn authorization does not
appear in the statement of current access at all. A statement that listed the withdrawn
authorization, even marked as withdrawn, would reflect the withdrawal incompletely, because it
would still present the withdrawn grantee among those the statement enumerates. The revocation-
exclusion rule (Section 7) serves SUP-CUST-11 by excluding revoked grants entirely, which is
the complete reflection the statement requires. The bank's rejected annotation proposal
(Appendix D, 2023-04-11) was rejected precisely because annotation is an incomplete reflection.

### H.4 REC-AUDIT-4 and the retention of historical states

REC-AUDIT-4, the recordkeeping standard, requires that historical states of key material remain
auditable. In the escrow context this means that the custodian must be able to demonstrate, for
any point in the past, what key material it held and under what custody — and, critically, that
it still holds the historical material needed to recover records from that past point. The
version-folding rule (Section 8) serves REC-AUDIT-4 by ensuring that the custody statement
enumerates every historical version, so that an auditor can confirm from the statement that no
historical version has been lost. A statement that listed only current versions would give an
auditor no basis to confirm the retention of historical material, and the audit contemplated by
REC-AUDIT-4 could not be performed from the statement.

### H.5 The interaction of the standards

The four standards interact to produce the four rule sets. SUP-CUST-7 requires accuracy;
SUP-CUST-7.3 sharpens accuracy into byte-reproducibility, producing the ordering and
normalization rules; SUP-CUST-11 requires complete reflection of revocation, producing the
revocation-exclusion rule; and REC-AUDIT-4 requires retention of historical states, producing
the version-folding rule. No rule serves only one standard — the normalization rules serve both
the fidelity dimension of SUP-CUST-7 and the reproducibility requirement of SUP-CUST-7.3, and
the version-folding rule serves both the completeness dimension of SUP-CUST-7 and the historical-
retention requirement of REC-AUDIT-4 — but the mapping above identifies the primary standard
behind each rule. An implementer who keeps the standards in view is less likely to introduce a
defect, because each of the 2023 defects can be recognized, in retrospect, as a violation of a
specific standard: the revocation defect violated SUP-CUST-11, the version-folding defect
violated REC-AUDIT-4, and the canonicalization defect violated SUP-CUST-7.3.

---

## Appendix I — Operational runbook for the supervised replay

This appendix is the operational runbook the custody engineering team follows during the
supervised replay. It is procedural rather than normative — it describes the steps of a
particular replay run — but it is included in the report so that the supervisor and any
independent reviewer can see exactly what the replay does and confirm that it matches the
rules. The runbook assumes the corrected pipeline has been built and its verification suite
passes (Appendix F, Section F.5).

### I.1 Pre-conditions

Before the replay begins, the operator confirms the following pre-conditions. The export
`assets/key-escrow-export.json` is present and is the export the supervisor drew for this
replay; its identifier matches the identifier the supervisor recorded. This report,
`docs/key-escrow-remediation-report.md`, is present and is this final version. The pipeline
build `target/key-escrow-service.jar` is present and was built from the corrected source. The
working directory is the project root, so that the relative paths for the export, the report,
and the manifest resolve (Section C.12). The operator records these confirmations in the replay
log.

### I.2 Starting the service

The operator starts the service with the entrypoint command, which binds the HTTP surface to
port 7070. The operator confirms the service is live by requesting the health probe and
observing a success response. The health probe is the pre-existing route that the remediation
preserved (Section 11 references it as a route that must keep working); confirming it responds
establishes that the service started cleanly before any migration is attempted.

### I.3 Triggering the replay

The operator triggers the replay by posting to the replay endpoint. The endpoint ingests the
export, writes the migration tables, emits the manifest, and returns a success response
carrying the manifest path and the record count. The operator confirms that the response
reports success, that the manifest path is the expected `custody_manifest.json`, and that the
record count matches the expected total folded version count for this export. A record count
that does not match the expected total is an immediate signal of the version-folding defect
(Section F.3) and, if observed, halts the replay pending investigation.

### I.4 Verifying the manifest

With the manifest emitted, the operator computes its SHA-256 digest and compares it against the
expected digest in the checksum artifact. A match confirms that the manifest is byte-identical
to the reference and that all rules were applied. A mismatch halts the replay; the operator then
runs the rule-by-rule verification suite (Appendix F) to identify which rule was violated, and
the identified defect is investigated and corrected before the replay is retried. The operator
does not proceed past a digest mismatch, because a mismatched manifest is not acceptable to the
supervisor.

### I.5 Confirming the migration tables

The operator confirms that the migration tables were written: the `key_versions` table has a row
count equal to the manifest's record count (every folded version), the `wrapping_certificates`
table has a row per certificate, and the `custody_grants` table has a row per grant in the export
including revoked grants (Section 10.3). The table confirmations are secondary to the manifest —
the manifest is the regulated deliverable — but the supervisor's on-site reconciliation queries
the tables, so the operator confirms they are present and populated.

### I.6 Sign-off

With a matching digest and confirmed tables, the operator presents the manifest to the
supervisory reviewer, who independently recomputes the digest from the export and the rules and
confirms it matches. On confirmation the reviewer signs the manifest (Section 11.3). The signed
manifest is the deliverable that discharges the replay obligation of the undertaking.

### I.7 On re-runs

The replay is idempotent: re-running it against the same export produces the same manifest and
the same table contents, because the schema application is idempotent (the DDL uses create-if-
not-exists) and the table population clears and rewrites the rows on each run. An operator may
therefore re-run the replay after correcting a defect without cleaning up a prior run's state;
the re-run overwrites it. Idempotency is a property of the pipeline, not a rule the manifest must
satisfy, but it is relied on operationally during the iterate-and-retry cycle of a replay that
initially fails verification.

---

## Appendix J — Data-model deep dive

This appendix discusses each field of the data model (Section 4) in more depth than the model
section, addressing the questions that arose about individual fields during implementation. It
is organized by entity and, within each entity, by field.

### J.1 The export's top-level fields

The `exportId` uniquely identifies the export instance and is the value carried into the
manifest as `generatedFrom`. It is a provenance field: it lets a reader of the manifest trace
back to the export the manifest was derived from. It is carried verbatim (not normalized),
because it is an identifier assigned by the archive and its exact form is significant.

The `jurisdiction` identifies the supervisory jurisdiction and is *not* carried into the
manifest (Section C.5). It is retained in the export for the archive's own bookkeeping and for
routing the export to the correct supervisor, but the manifest's provenance is fully captured by
`generatedFrom` and `manifestVersion`, and adding `jurisdiction` to the manifest would introduce
a seventh top-level member not in the specification.

The three collection fields — `escrowedKeys`, `wrappingCertificates`, `custodyGrants` — are
arrays whose ordering in the export is not significant (Section 4.1). The pipeline imposes
ordering per Section 9; it does not inherit the export's ordering. An implementer must resist the
temptation to preserve the export's ordering "because it might mean something"; it does not, and
preserving it would produce a non-canonical manifest whenever the export's ordering differs from
the canonical ordering.

### J.2 Escrowed-key fields

The `keyId` is the stable identifier of the key material. It is normalized to lower case (Rule
B-1) and is the sort key for the `keyVersions` array in its normalized form (Rule E-1). Its
normalized form appears in the manifest both as the key entry's `keyId` and as the `keyId` of any
wrapping certificate that references the key. An implementer should normalize the identifier once,
early (in the normalize stage), and use the normalized form consistently thereafter, so that the
same key is identified identically wherever it appears.

The `versions` array holds the key's version chain. Its ordering in the export is not significant;
the pipeline folds all its elements (Rule D-1) and orders them by integer version (Rule E-4). The
array has at least one element (a key with no versions would be a malformed archive entry and is
not contemplated).

### J.3 Key-version fields

The `version` is a positive integer, the primary key of the version within its key, and the sort
key for the folded `versions` array (Rule E-4, integer comparison). It is carried verbatim.

The `algorithm` is normalized to upper case (Rule B-4). It records the cryptographic algorithm the
version used. Two versions of a key may share an algorithm; they are still distinct versions and
are not merged (Section 8.3).

The `custodyStatus` records the version's lifecycle state and is carried verbatim (not normalized;
Section 6.1). It does not affect folding (Section 8.5): every version is folded regardless of
status. It is descriptive, not selective.

The `createdEpoch` is the version's creation time in seconds since the Unix epoch, carried verbatim
as an integer (Section C.7). It is not reformatted.

### J.4 Wrapping-certificate fields

The `certId` is the certificate's stable identifier, the sort key for the `wrappingCertificates`
array (Rule E-3, string comparison on the zero-padded form), and is carried verbatim (the
certificate's own identifier is not normalized; only the `keyId` it references is normalized).

The `subject` is the certificate's distinguished name, normalized by whitespace collapse (Rule B-2:
trim and collapse internal whitespace runs to single spaces). It is not otherwise reformatted; the
distinguished name's component order and content are preserved, only its whitespace is canonicalized.

The `keyId` references the escrowed key the certificate protects and is normalized to lower case
(Rule B-1), consistent with the normalization of the key's own identifier, so that the reference
matches the key.

The `notAfterEpoch` is the certificate's expiry in seconds since the Unix epoch, carried verbatim.
It does not affect grant survival (Section C.4); an expired certificate's non-revoked grants
survive.

### J.5 Custody-grant fields

The `grantId` is the grant's stable identifier, the sort key for the surviving-grants array (Rule
E-2, string comparison on the zero-padded form), and is carried verbatim.

The `principal` is the identity the grant authorizes, normalized by trimming (Rule B-3: trim only,
no internal collapse). It is not lower-cased or otherwise altered beyond trimming, because a
principal's internal characters and case may be significant (Section B.2).

The `certId` references the wrapping certificate the grant applies to. It is carried on the grant
object and its referenced-key normalization does not apply to it directly; the grant references a
certificate by the certificate's own identifier, which is not normalized. (The normalization that
applies is to `keyId` references, not `certId` references.) An implementer should take care to
normalize `keyId` references and to leave `certId` references verbatim, per the field-by-field
rules.

The `revoked` flag is the boolean that governs the grant's survival (Rule C-1). It is the sole
determinant of exclusion (Section 7.3) and is not itself carried into the manifest, because a
surviving grant is by definition not revoked and the flag would be uniformly false and therefore
uninformative. (The flag is carried into the `custody_grants` migration table, which retains all
grants including revoked ones; Section 10.3.)

The `grantedEpoch` is the grant's issuance time in seconds since the Unix epoch, carried verbatim.
It is not a selection criterion; a grant's age does not affect its survival (Section 7.3).

---

## Appendix K — Custodian and auditor frequently asked questions

The questions in this appendix were raised not by the engineering team (whose questions are in
Appendix C) but by the custodians who operate under the grants and by the internal auditors who
review the custody statements. They are answered here because they bear on how the manifest is
read, which in turn confirms why the rules are what they are.

### K.1 (Custodian) I was granted custody and then my grant was revoked. Why don't I appear in the manifest?

Because the manifest is a statement of *current* custody, and a revoked grant confers no current
custody (Section 7.1, Section 1.2). Your grant, including its revocation, is retained in the
archive and in the working migration tables (Section 10.3), so the history of your access is not
lost; it simply does not appear in the current-custody statement, because you do not currently
hold custody. If your grant were reinstated (a new, non-revoked grant issued), you would appear in
subsequent manifests.

### K.2 (Custodian) I hold a grant against a certificate that has expired. Why do I still appear?

Because grant survival depends on the grant's revocation flag, not on the referenced
certificate's expiry (Section C.4, Section 7.3). Your non-revoked grant survives into the manifest
even though the certificate it references has expired. The certificate's expiry is recorded in the
manifest (via its `notAfterEpoch`) so a reader can see it has expired, but the expiry does not
withdraw your grant. If the intent is to withdraw access when a certificate expires, that must be
effected by revoking the grant, not merely by letting the certificate expire.

### K.3 (Auditor) How do I confirm that no historical key version was dropped?

By comparing the manifest's per-key version lists, and its `recordCount`, against the archive's
version records. The manifest folds every version (Section 8.1) and reports the total folded count
as `recordCount` (Section 8.4), so a manifest that dropped a version would show a shorter version
list for the affected key and a lower `recordCount` than the archive supports. The version-folding
verification check (Section F.3) performs exactly this comparison for a multi-version key, and an
auditor can perform it for every key.

### K.4 (Auditor) How do I confirm the manifest I hold is the authentic one?

By recomputing its SHA-256 digest and comparing against the expected digest the supervisor
recorded (Section 11.1). Because the manifest is a canonical serialization (Sections 5 and 6), any
conforming recomputation from the same export produces the same bytes and therefore the same
digest; a manifest whose digest matches is byte-identical to the reference and is authentic. A
manifest whose digest does not match has been altered or was produced by a non-conforming pipeline
and should not be relied on.

### K.5 (Auditor) Why is the manifest ordered lexicographically rather than in a logical order?

Because lexicographic order is reproducible without a maintained convention, whereas a logical
order would require a maintained table that could drift between the parties who compute and verify
the manifest (Section 5.2, Section B.1). The manifest is optimized for reproducible verification,
not for human reading; an auditor who wants a logically-ordered view can reorder a parsed copy for
reading, but the canonical bytes — the ones that are hashed — are lexicographically ordered.

### K.6 (Custodian) The manifest shows my principal without the spaces I entered. Is that a change to my identity?

No. Leading and trailing whitespace around a principal is incidental formatting introduced during
grant issuance and carries no meaning; the manifest trims it (Rule B-3) so that the same identity
always serializes identically. Your identity's significant characters — everything but the
surrounding whitespace — are preserved exactly. Internal whitespace, if your principal had any,
would also be preserved (Section A.4); only the surrounding whitespace is trimmed.

### K.7 (Auditor) A certificate appears in the manifest but no grant references it. Is that an error?

No. A certificate is carried into the manifest on its own terms, independently of whether any
surviving grant references it (Section 7.4). If a certificate's only grants were revoked, the
certificate still appears (recording its existence and expiry) while none of its grants appear.
This is expected and not an error; it indicates that the certificate exists but that no principal
currently holds custody against it.

---

## Appendix L — Control mapping

This appendix maps each rule to the supervisory control it implements and to the verification
check that confirms it, so that a reviewer can trace each rule from its regulatory basis to its
test. The mapping is presented as prose rather than a table so that each entry can carry the
explanation that makes the mapping meaningful.

The **canonical key ordering** rule (Section 5) implements the reproducibility control of
SUP-CUST-7.3 (Appendix H.2): it fixes the arrangement of object members so that identical input
and rules produce identical bytes. It is confirmed by the canonical-ordering verification check
(Section F.1), which asserts lexicographic member order at the top level and in a representative
nested object, and ultimately by the digest match (Section 11.1), which fails on any ordering
deviation.

The **value normalization** rules (Section 6) implement both the fidelity dimension of SUP-CUST-7
(Appendix H.1) and the reproducibility control of SUP-CUST-7.3 (Appendix H.2): they fix the form of
each value so that the same fact serializes identically. They are confirmed by the value-
normalization verification check (Section 11.2), which asserts lower-cased key identifiers, upper-
cased algorithms, whitespace-collapsed subjects, and trimmed principals, and by the digest match.

The **revocation exclusion** rule (Section 7) implements the completeness-of-revocation control of
SUP-CUST-11 (Appendix H.3): it excludes revoked grants entirely, reflecting each withdrawal
completely. It is confirmed by the revocation verification check (Section F.2), which asserts the
absence of revoked grants and the presence of survivors, and by the digest match.

The **version folding** rule (Section 8) implements both the completeness dimension of SUP-CUST-7
(Appendix H.1) and the historical-retention control of REC-AUDIT-4 (Appendix H.4): it folds every
version so that no held material is omitted and the historical audit trail is preserved. It is
confirmed by the version-folding verification check (Section F.3), which asserts that every version
of a multi-version key appears in order and that the record count equals the total folded count,
and by the digest match.

The **ordering constraints** (Section 9) implement the reproducibility control of SUP-CUST-7.3
(Appendix H.2) for array elements, the counterpart of the canonical key ordering rule for object
members: they fix the arrangement of array elements so that identical input produces identical
bytes. They are confirmed by the digest match, which fails on any element-ordering deviation, and
by the version-folding check's assertion of ascending version order.

The mapping shows that no rule is arbitrary: each traces to a specific control and is confirmed by a
specific check. A reviewer confirming the remediation can walk the mapping in either direction — from
a control to the rules that implement it and the checks that confirm them, or from a check back to
the rule it confirms and the control that rule serves.

---

## Appendix M — Extended serialization walk-through

This appendix walks the serialization of the accompanying export's manifest field by field, so that
an implementer can compare their output against a fully-described reference at the level of
individual members. It does not reproduce the exact bytes (which the implementer's own tooling will
produce) but describes them precisely enough to check against.

### M.1 The top-level object

The top-level object opens and, in lexicographic member order, contains: `custodyGrants` (an array),
then `generatedFrom` (a string, the export's identifier), then `keyVersions` (an array), then
`manifestVersion` (the integer 1), then `recordCount` (the integer 6 for this export), then
`wrappingCertificates` (an array). An implementer whose top-level object opens with any member other
than `custodyGrants` has a member-ordering defect: `custodyGrants` is lexicographically first among
the six member names and must appear first.

### M.2 The custodyGrants array

The `custodyGrants` array contains the three surviving grants, in `grantId` order: first the grant
`CG-0001`, then `CG-0003`, then `CG-0007`. Each grant object, in lexicographic member order, contains
`certId` (the lower-cased certificate reference), `grantId`, `grantedEpoch` (the integer epoch), and
`principal` (the trimmed identity). The grant `CG-0007`'s principal is the trimmed
`ops.custodian@custodian-bank.example`, with the surrounding whitespace of the export removed. The
revoked grants `CG-0002` and `CG-0005` are absent from this array.

### M.3 The keyVersions array

The `keyVersions` array contains the three keys in normalized-identifier order: first `kms-root-alpha`,
then `kms-root-mu`, then `kms-root-zeta`. Each key object, in lexicographic member order, contains
`keyId` (the lower-cased identifier) and `versions` (the folded, version-ordered array). The
`kms-root-alpha` entry's `versions` array has two elements (versions 1 and 2, in that order); the
`kms-root-mu` entry's has one (version 1); the `kms-root-zeta` entry's has three (versions 1, 2, 3, in
that order). Each version object, in lexicographic member order, contains `algorithm` (upper-cased),
`createdEpoch` (the integer epoch), `custodyStatus` (verbatim), and `version` (the integer). For
example, `kms-root-zeta`'s first version object is version 1 with algorithm `AES-128-GCM`, created
epoch 1678924800, custody status `retired`.

### M.4 The wrappingCertificates array

The `wrappingCertificates` array contains the certificates in `certId` order: first `WC-001`, then
`WC-002`, then `WC-004`. Each certificate object, in lexicographic member order, contains `certId`
(verbatim), `keyId` (the lower-cased referenced key), `notAfterEpoch` (the integer epoch), and
`subject` (the whitespace-collapsed distinguished name). For example, `WC-004`'s subject, collapsed
from the export's `CN=Escrow Wrapping   Authority,  O=Custodian Bank,C=DE`, is
`CN=Escrow Wrapping Authority, O=Custodian Bank,C=DE`.

### M.5 The scalar members

The `generatedFrom` member is the export's identifier string, carried verbatim. The `manifestVersion`
member is the integer 1. The `recordCount` member is the integer 6, the total folded versions
(2 + 1 + 3). An implementer whose `recordCount` is 3 (the number of keys) has the version-folding
defect; an implementer whose `recordCount` is 6 but whose per-key version arrays have only one element
each has an inconsistency between the count and the arrays that also indicates the defect.

---

## Appendix N — Rejected design alternatives

During the drafting of the rules, several alternative designs were considered and rejected. They
are recorded here so that an implementer or reviewer who conceives of one of them does not have to
re-derive why it was rejected. Each entry states the alternative, the argument for it, and the
reason it was rejected.

### N.1 Annotating rather than excluding revoked grants

The alternative, discussed at length in Section 7.2 and Section B.3 and logged in Appendix D
(2023-04-11), was to carry every grant into the manifest with a `revoked` marker rather than
excluding revoked grants. The argument for it was that it "loses no information." It was rejected
because the manifest is a statement of *current* custody, and listing a revoked grantee — even
marked — reflects the revocation incompletely, contrary to SUP-CUST-11 (Appendix H.3), and adds
canonicalization surface area (the marker's representation) that exclusion avoids.

### N.2 Reducing each key to its latest version

The alternative, which was in fact the 2023 defect (Section G.2), was to represent each key by its
single most recent version. The argument for it was that the current version is "the one of
interest." It was rejected — indeed identified as a defect — because the escrow programme requires an
all-material statement, not a current-state view, and dropping historical versions breaks the audit
trail required by REC-AUDIT-4 (Appendix H.4). Every version is folded (Section 8).

### N.3 A maintained logical member order

The alternative, logged in Appendix D (2023-08-01), was to fix a "logical" member order (provenance,
then payload arrays, then counts) and maintain it in a table both parties would keep. The argument
for it was readability. It was rejected because a maintained table is a source of drift between the
computing and verifying parties, defeating the reproducibility it was meant to serve, whereas
lexicographic order is self-describing and requires no maintained table (Section 5.2, Section B.1).

### N.4 Minified rather than pretty-printed output

An alternative considered was to serialize the manifest minified (no indentation) to reduce its
size. The argument for it was compactness. It was rejected because the manifest is read by human
reviewers during reconciliation, and a pretty-printed manifest is far easier to read; the size cost
is negligible for a manifest of the sizes the archive produces. What matters for reproducibility is
that both parties serialize the same way (Section C.10), and the agreed way is pretty-printed with
sorted keys. An implementer must match the agreed style; a minified manifest, though canonical in
member order, would not match the reference's bytes and would fail the digest check.

### N.5 Normalizing principals more aggressively

An alternative considered was to lower-case principals and collapse their internal whitespace, by
analogy with certificate subjects. The argument for it was uniformity of normalization. It was
rejected because a principal is an opaque identity string whose case and internal characters may be
significant, whereas a distinguished name's spacing is incidental (Section B.2, Section A.4). Over-
normalizing a principal risks corrupting an identity, which is a worse error than leaving incidental
formatting; the rule therefore trims principals only.

### N.6 Sorting versions descending

An alternative considered was to order each key's folded versions descending (latest first), so that
a reader sees the current version at the top. The argument for it was reader convenience. It was
rejected in favour of ascending order (Section 9.4) because ascending order reads as a history from
origin to present, which matches the audit purpose of the version list, and because a single
consistent direction is required for reproducibility and ascending was chosen. Either direction
would be reproducible if fixed; ascending was fixed.

### N.7 Deriving revocation from certificate expiry

An alternative considered was to treat a grant as revoked when its referenced certificate had
expired, on the theory that an expired certificate confers no usable access. The argument for it was
that it would automatically retire access when certificates lapsed. It was rejected because grant
survival must depend only on the grant's own revocation flag (Section 7.3), and conflating
certificate expiry with grant revocation would both retire grants that should survive (a non-revoked
grant against an expired certificate) and obscure the distinction between the two lifecycle events.
Expiry and revocation are separate facts, recorded separately, and only revocation removes a grant.

---

## Appendix O — The manifest as a security artifact

This appendix discusses the manifest's role in the escrow programme's security posture, to explain
why its correctness is a security matter and not merely a recordkeeping one. The discussion is
background; it does not add rules.

### O.1 The manifest bounds the access surface

The manifest enumerates the principals who currently hold custody. That enumeration is the access
surface of the escrow archive: the set of parties who could, under the programme's procedures,
recover escrowed material. A supervisor assessing the archive's security assesses this surface — how
many principals, of what kinds, under what controls. A manifest that overstated the surface (by
listing revoked grantees, the revocation defect) would cause the supervisor to assess a larger
surface than actually exists, which is a conservative error but still an inaccuracy; a manifest that
understated the surface would cause the supervisor to assess a smaller surface than exists, which is
a dangerous error because it would hide access that in fact exists. The revocation rule ensures the
surface is stated exactly: neither more nor fewer principals than currently hold custody.

### O.2 The manifest bounds the recoverable-material surface

The manifest's version folding enumerates the key material the archive holds, active and historical.
That enumeration bounds the recoverable-material surface: the set of records that could be recovered
with the held material. A manifest that understated this surface (by dropping historical versions,
the version-folding defect) would hide the archive's ability to recover historical records, which is
dangerous in the opposite direction from O.1: it would cause the supervisor to believe the archive
could not recover records it in fact can, which understates the archive's power and the corresponding
need for controls over that power. The version-folding rule ensures the recoverable-material surface
is stated completely.

### O.3 The digest binds the manifest to a point in time

The manifest's SHA-256 digest binds the manifest's exact content to a verifiable fingerprint. Once
the supervisor records the expected digest, any manifest presented as *the* manifest for that export
can be checked against it: a matching digest proves the manifest is the one the rules produce from
that export, and a mismatch proves it is not. The digest thereby prevents both accidental drift (a
manifest regenerated by a non-conforming pipeline) and deliberate tampering (a manifest altered after
generation); either changes the bytes and therefore the digest. The canonicalization rules are what
make the digest a reliable binding: without them, an honestly-regenerated manifest could differ in
bytes and its digest would mismatch even though its content was correct, undermining the binding. The
canonicalization thus serves security by making the digest trustworthy.

### O.4 Why hard-coding the digest is a security failure

Section 2.2 forbids embedding the expected digest in the pipeline. Beyond the reproducibility argument
(a pipeline that reproduces a stored answer does not genuinely recompute the manifest), there is a
security argument: a pipeline that embeds the expected digest, or that special-cases the sample export
to produce a known manifest, would produce the "right" digest even if its rule logic were broken,
because it would be asserting the answer rather than computing it. Such a pipeline would pass the
digest check while being incapable of correctly processing a *different* export, which is exactly the
capability the supervised replay is meant to establish. The prohibition on embedding the digest ensures
that passing the digest check actually demonstrates a correct pipeline, which is what the check is for.

---

## Appendix P — Extended glossary

This appendix expands the glossary of Section 12 with the cryptographic and regulatory terms used in
the report, defined at more length than the compact glossary allows.

**Escrow.** The holding of cryptographic key material by a custodian on behalf of a principal, so
that the material can be recovered under defined circumstances even if the principal cannot or will
not produce it. The escrow programme (Section 1.1) is the bank's regulated implementation of escrow
for institutional clients.

**Key material.** The secret data — a private key, a symmetric key — whose possession enables
decryption of protected records. Escrowed key material is held so protected records remain recoverable.

**Key version.** One rotation of a key. Keys are rotated over their lifetime for cryptographic
hygiene, and each rotation produces a new version with a new version number; earlier versions are
retained because they protect records encrypted before the rotation (Section 4.5, Section 8.2).

**Rotation.** The act of replacing a key's active material with new material, producing a new version.
Rotation does not discard the prior version's material, which the archive retains.

**Wrapping.** The encryption of key material under the public key of a wrapping certificate, so that
the material can be stored and transported without being exposed in the clear. Only the holder of the
wrapping certificate's private key can unwrap the material.

**Wrapping certificate.** The certificate whose public key wraps escrowed key material and whose
private key a custodian holds under a custody grant (Section 1.1, Section 4.3).

**Custody.** The authorized ability to exercise control over escrowed material — in practice, to
unwrap it — conferred by a custody grant. The custody manifest states who currently holds custody.

**Custody grant.** The authorization conferring custody on a named principal over a named wrapping
certificate (Section 1.2, Section 4.4). Revocable.

**Revocation.** The withdrawal of a custody grant, recorded by the grant's revocation flag. A revoked
grant confers no custody and is excluded from the manifest (Section 7).

**Principal.** The identity a custody grant authorizes — a human custodian or a named service identity
(Section 4.4).

**Canonicalization.** The process of putting a document into a canonical form with a single correct
byte layout for a given input, here by fixing member order (Section 5) and normalizing values (Section
6). Canonicalization makes byte-level reproducibility possible.

**Digest.** The SHA-256 hash of the canonical manifest's bytes, used to verify the manifest's
authenticity (Section 11.1, Appendix O.3).

**Reproducibility.** The property, required by SUP-CUST-7.3, that two parties computing the manifest
from the same export and rules produce byte-identical results (Appendix H.2). Canonicalization is what
achieves it.

**Fold / folding.** The gathering of a key's complete version chain into the single `versions` array of
its manifest entry, in ascending version order (Section 8, Rule E-4).

**Manifest.** The regulated deliverable: the canonical JSON statement of current custody derived from
the export by the rules of this report (Section 4.6).

---

## Appendix Q — History of the escrow programme

This appendix narrates the history of the escrow programme, from its origin through the platform
migrations that shaped the current architecture, so that the reader understands the accumulated
context in which the current archive and its export exist. The history is background; it explains why
the archive has the shape it does — mixed-case identifiers, versioned keys, an unordered export — and
therefore why the rules that normalize and order the manifest are needed.

### Q.1 Origins (2011–2015)

The escrow programme began in 2011 as a manual arrangement: a small number of institutional clients
deposited encrypted backups with the bank under contractual undertakings that the bank would hold the
decryption keys in escrow and produce them only under legally compelled circumstances. In this first
era the keys were held as files on isolated media, and custody was recorded in a ledger by hand. There
was no versioning — a client's key was a single key — and there was no automated statement of custody;
when the supervisor asked who held custody, the answer was compiled by reading the ledger.

The first-era arrangement did not scale. As the client base grew, the manual ledger became error-prone,
and the absence of key versioning meant that when a client rotated its encryption keys — as prudent
clients did — the bank either had to treat the rotated key as a new escrow deposit (losing the linkage
to the prior deposit) or overwrite the prior key (losing the ability to recover records the prior key
protected). Neither was satisfactory, and the tension between them motivated the introduction of
versioning in the second era.

### Q.2 The versioned era (2015–2019)

In 2015 the programme adopted a versioned key model: each escrowed key became a stable identifier with
a chain of versions, one per rotation, all retained. This solved the rotation tension — a rotation added
a version rather than replacing or duplicating a key — and it established the versioned data model the
current archive still uses (Section 4.2, Section 4.5). The custody ledger was partially automated in
this era, but custody statements were still compiled semi-manually, and the statement's treatment of
versions was inconsistent: some statements listed all versions, others only the current one, depending
on who compiled them. This inconsistency is the distant ancestor of the version-folding defect
(Section G.2); the current remediation resolves it by mandating that every version be folded (Section 8).

The versioned era also saw the first use of wrapping certificates. Before 2015, escrowed keys were held
as files protected by disk encryption; from 2015 they were wrapped under certificates, so that custody
could be conferred by holding a certificate's private key rather than by physical access to media. The
wrapping-certificate model established the custody-grant concept (Section 1.2): a grant conferred custody
by authorizing a principal to hold and use a wrapping certificate's private key.

### Q.3 The platform migration (2019)

In 2019 the programme migrated to a hardware-backed key-management platform, the architecture the current
archive reflects. The migration moved key material into hardware security modules, so that material never
leaves the hardware boundary in the clear (Section 1.1), and it formalized the custody-grant model into
the structured grants the archive now records (Section 4.4), including the revocation flag that records
withdrawal. The migration was, at the data level, a translation of the versioned-era records into the
platform's schema, and it is where several of the archive's incidental irregularities originated: the
mixed-case key identifiers arose because the migration preserved the identifiers as the versioned-era
systems had recorded them, and different versioned-era systems had capitalized them differently; the
inconsistent certificate-subject spacing arose because the migration imported subjects from multiple
certificate authorities with different formatting conventions; and the incidental whitespace around some
principals arose from copy-and-paste during the migration's grant-issuance step. The normalization rules
of Section 6 exist to erase exactly these migration-era irregularities.

### Q.4 The export mechanism

The platform exposes the archive through a nested JSON export (Section 1.3, Section 4.1), which is the
input to the migration replay this report governs. The export mechanism dumps the archive faithfully,
in whatever internal order the platform's storage happens to yield, and including revoked grants and
historical versions, because it is a dump rather than a curated view. The export's arbitrary ordering
is why the manifest must impose its own ordering (Section 9), and the export's inclusion of revoked
grants and historical versions is why the manifest must apply its own selection (Sections 7 and 8). The
export is not the manifest; the manifest is what the rules produce from the export.

### Q.5 The 2023 reconciliation and this remediation

The 2023 reconciliation (Section 3, Appendix G) exposed that the statement-generation pipeline, inherited
and extended across these eras, contained the three defects this report remediates: it copied revoked
grants (a versioned-era inconsistency never resolved), it reduced keys to their latest version (a
versioned-era inconsistency never resolved), and it serialized without canonicalization (an issue that
only surfaced when byte-level verification was introduced). The remediation resolves all three by stating
the rules explicitly and correcting the pipeline to apply them. The history above explains why the
defects existed: each was a latent inconsistency carried forward across the programme's evolution, never
resolved because no prior control cross-checked the statement against the archive at the byte level. The
byte-level reconciliation introduced in 2023 is the control that exposed them, and this report is the
specification that resolves them.

---

## Appendix R — The reconciliation procedure

This appendix documents the reconciliation procedure the supervisor follows, so that an implementer
understands the check their manifest will face. The procedure is the supervisor's, not the bank's; it is
documented here for the implementer's benefit.

### R.1 Drawing the inputs

The supervisor draws the archive export and the bank's produced manifest for the period under
reconciliation. The export is the authoritative input; the manifest is the artifact under test. The
supervisor confirms that the export's identifier matches the identifier the manifest records as its
`generatedFrom`, so that the manifest is being reconciled against the export it claims to derive from.

### R.2 Independent recomputation

The supervisor recomputes the manifest from the export using an independent implementation of the rules
of this report. Because the manifest is canonical (Sections 5 and 6), the independent recomputation, if
both implementations conform, produces byte-identical output. The supervisor computes the digest of its
recomputed manifest and compares it against the digest of the bank's manifest.

### R.3 Digest comparison

A matching digest ends the reconciliation successfully: the bank's manifest is byte-identical to the
supervisor's independent recomputation, so it conforms to the rules. A mismatching digest triggers the
rule-by-rule diagnosis of R.4, to identify which rule the bank's manifest violated.

### R.4 Rule-by-rule diagnosis

On a digest mismatch, the supervisor parses both manifests and compares them rule by rule: it checks the
member ordering (a mismatch indicates the canonicalization defect), the presence of revoked grants (a
mismatch indicates the revocation defect), the completeness of version folding (a mismatch indicates the
version-folding defect), and the value normalizations (a mismatch indicates a normalization defect). The
diagnosis identifies the specific defect, which the bank then corrects before re-producing the manifest.
The rule-by-rule diagnosis mirrors the verification checks of Section 11.2, which is why an implementer
who passes those checks before the reconciliation will pass the reconciliation.

### R.5 On-site table reconciliation

Beyond the manifest, the supervisor's on-site reconciliation queries the migration tables (Section 10):
it confirms that the `key_versions` table's row count matches the manifest's record count, that the
`wrapping_certificates` table has the expected certificates, and that the `custody_grants` table retains
all grants including revoked ones. The table reconciliation confirms that the working store is consistent
with the manifest and with the archive; a discrepancy between the table and the manifest — for example a
manifest that folds all versions but a table that stored only the latest — would indicate a pipeline that
treats the two outputs inconsistently.

### R.6 Outcome

A reconciliation that passes the digest comparison and the on-site table reconciliation is signed off by
the supervisory reviewer (Section 11.3), and the signed manifest discharges the reconciliation. A
reconciliation that fails at any step is recorded as a finding, the identified defect is remediated, and
the reconciliation is repeated. The remediation this report specifies is intended to produce a manifest
that passes the reconciliation on the first attempt, which it does when the pipeline conforms to every
rule.

---

## Appendix S — Worked negative examples

Appendix A worked the correct output; this appendix works the *incorrect* output each defect produces on
the accompanying export, so that an implementer can recognize a defect's signature in their own output.
Each entry describes the wrong output and the specific way it differs from the correct output of Appendix
A and M.

### S.1 The canonicalization defect's output

A pipeline exhibiting only the canonicalization defect (revocation and folding correct, member ordering
wrong) produces a manifest whose *content* matches Appendix M exactly — three surviving grants, all
versions folded, correct normalizations — but whose top-level members appear in the order the building
code populated them rather than lexicographically. If the building code populates in the order
`manifestVersion`, `generatedFrom`, `custodyGrants`, `keyVersions`, `wrappingCertificates`, `recordCount`,
then the manifest opens with `manifestVersion` rather than `custodyGrants`, and the six members are in
that populate-order rather than the lexicographic order `custodyGrants`, `generatedFrom`, `keyVersions`,
`manifestVersion`, `recordCount`, `wrappingCertificates`. The nested objects may likewise be in populate-
order. The digest of this output differs from the reference digest even though every value is correct.
The canonicalization verification check (Section F.1) fails on the top-level ordering assertion. The
signature of this defect is: content correct, member ordering wrong, digest mismatched.

### S.2 The revocation defect's output

A pipeline exhibiting only the revocation defect (ordering and folding correct, revoked grants carried)
produces a manifest whose `custodyGrants` array has *five* elements rather than three, because it carries
the revoked `CG-0002` and `CG-0005` alongside the survivors `CG-0001`, `CG-0003`, `CG-0007`. The five
grants are correctly ordered by `grantId` (`CG-0001`, `CG-0002`, `CG-0003`, `CG-0005`, `CG-0007`) and
correctly normalized, but two of them should not be present at all. The `keyVersions`, `wrappingCertificates`,
`manifestVersion`, `generatedFrom`, and `recordCount` members are unaffected (revocation affects only the
grants array; Section 7.5). The digest differs from the reference because the grants array differs. The
revocation verification check (Section F.2) fails on the absence assertion for `CG-0002` and `CG-0005`.
The signature of this defect is: five grants instead of three, revoked identifiers present, digest
mismatched.

### S.3 The version-folding defect's output

A pipeline exhibiting only the version-folding defect (ordering and revocation correct, only latest version
kept) produces a manifest whose `keyVersions` entries each have a *single* version — the latest — rather
than the full folded chain. The `kms-root-zeta` entry has one version (version 3) instead of three; the
`kms-root-alpha` entry has one version (version 2) instead of two; the `kms-root-mu` entry has one version
(version 1, which is also its latest, so unchanged). The `recordCount` is 3 (one version per key) rather
than 6 (the total versions). The `custodyGrants` and `wrappingCertificates` members are unaffected. The
digest differs from the reference because the key-versions array and the record count differ. The version-
folding verification check (Section F.3) fails on the per-key version-count assertion for `kms-root-zeta`
and on the record-count assertion. The signature of this defect is: one version per key, record count
equal to key count, digest mismatched.

### S.4 Combinations of defects

A pipeline exhibiting more than one defect produces output combining the signatures above. A pipeline with
all three defects — the state of the 2023 pipeline — produces a manifest with members in populate-order,
five grants including the revoked ones, one version per key, and a record count of three; its digest
differs from the reference and it fails all three rule-by-rule checks. An implementer correcting the defects
one at a time will see the failing checks resolve one at a time: correcting revocation resolves the
revocation check (grants drop to three) but leaves the ordering and folding checks failing; correcting
folding then resolves the folding check (versions fold, record count becomes six) but leaves the ordering
check failing; correcting ordering last resolves the ordering check and, with all three corrected, the
digest matches. There is no ordering dependency among the corrections — they can be made in any order — but
the digest matches only when all three are made.

---

## Appendix T — Governance and roles

This appendix records the governance roles in the remediation, so that the reader knows who is accountable
for what. It is organizational context; it does not add rules.

### T.1 The Chief Custody Officer

The Chief Custody Officer owns the escrow programme and is accountable to the supervisor for its custody
posture. The remediation is performed under the Chief Custody Officer's authority, and the undertaking of
Section 2.2 was given by the Chief Custody Officer's office. The Chief Custody Officer signs the report and
is accountable for the pipeline's conformance to the rules.

### T.2 The custody engineering team

The custody engineering team implements and operates the migration pipeline. It is accountable for building
a pipeline that conforms to the rules of this report, for running the verification suite before the
supervised replay, and for operating the replay per the runbook (Appendix I). The team's implementation
choices are advised by Appendix E but are the team's own; the team is accountable for the pipeline's
conformance regardless of its structure.

### T.3 The supervisory reviewer

The supervisory reviewer, acting for the supervisor, performs the independent reconciliation (Appendix R),
computes the expected digest from the reference implementation, and signs the manifest on a successful
reconciliation. The reviewer is independent of the engineering team, so that the reconciliation is a genuine
independent check rather than a self-check. The reviewer's sign-off attests that the reviewer's independent
recomputation matched the bank's manifest, which is the substance of the reproducibility control.

### T.4 Internal audit

Internal audit reviews the remediation for adherence to the bank's controls and to the supervisory
expectations, and confirms that the investigation (Appendix G) identified and addressed the root causes of
the defects rather than only their symptoms. Internal audit's questions are recorded in Appendix K. Internal
audit is independent of both the engineering team and the Chief Custody Officer's operational line, so that
its review is a genuine independent assurance.

### T.5 Separation of duties

The roles are separated so that no single party both produces and verifies the manifest: the engineering
team produces it, the supervisory reviewer verifies it independently, and internal audit assures the process.
The separation is what makes the reproducibility control meaningful — a manifest verified by its own producer
would be a self-check, whereas a manifest verified by an independent reviewer recomputing it from the archive
is a genuine check. The prohibition on embedding the digest (Section 2.2, Appendix O.4) supports the
separation: it ensures the engineering team's pipeline genuinely computes the manifest, so that the reviewer's
independent computation is a meaningful comparison rather than a comparison against a stored answer.

---

## Appendix U — Consolidated normative summary

This appendix consolidates the normative rules of the report into a single list, for reference. It restates
rather than extends the rules; in case of any discrepancy, the rule as stated in the body governs. The list
is offered so that an implementer can check off each rule against their implementation.

1. **Rule A-1 (Section 5.1, 5.2, 5.3):** In the serialized manifest, the members of every object, at every
   level, appear in lexicographic ascending order of key name. The serializer sorts keys; it does not rely
   on insertion order.

2. **Rule B-1 (Section 6.2):** Every key identifier is normalized to lower case, before it is used as a sort
   key.

3. **Rule B-2 (Section 6.3):** Every certificate subject is trimmed and has internal whitespace runs
   collapsed to single spaces.

4. **Rule B-3 (Section 6.4):** Every surviving grant's principal is trimmed of leading and trailing
   whitespace; internal whitespace is preserved.

5. **Rule B-4 (Section 6.5):** Every key version's algorithm is normalized to upper case.

6. **Rule C-1 (Section 7.1):** A grant whose revocation flag is true is excluded from the manifest; only
   non-revoked grants are carried.

7. **Rule D-1 (Section 8.1):** Every version of every key is folded into the manifest; no version is dropped.

8. **Rule D-2 (Section 8.4):** The record count equals the total number of folded versions across all keys.

9. **Rule E-1 (Section 9.1):** Keys in the key-versions array are ordered by normalized key identifier,
   ascending.

10. **Rule E-2 (Section 9.2):** Surviving grants are ordered by grant identifier, ascending.

11. **Rule E-3 (Section 9.3):** Certificates are ordered by certificate identifier, ascending.

12. **Rule E-4 (Section 9.4):** Versions within a key are ordered by integer version, ascending.

13. **Table rule (Section 10.3):** All grants, including revoked ones, are written to the custody-grants
    migration table; the exclusion of revoked grants is a property of the manifest only.

14. **Provenance (Section 5.2):** The manifest carries `generatedFrom` (the export identifier) and
    `manifestVersion` (the integer 1); it does not carry `jurisdiction`.

15. **No embedded digest (Section 2.2):** The pipeline derives the manifest from the export and the rules; it
    does not embed the expected digest or special-case any export.

An implementation that satisfies all fifteen items produces the canonical manifest whose digest matches the
supervisor's expected value, passes every verification check of Section 11.2, and passes the reconciliation
of Appendix R. This is the state the remediation exists to achieve.

---

*End of report KE-REM-2024-0417.*

---

## Appendix V — Rule interactions in depth

The rules of Sections 5 through 9 are stated independently, but they interact, and several of the
subtler implementation errors arise not from getting a single rule wrong but from getting the
interaction between two rules wrong. This appendix treats the interactions in depth.

### V.1 Normalization before ordering

The most consequential interaction is between normalization (Section 6) and ordering (Section 9),
specifically between Rule B-1 (lower-case key identifiers) and Rule E-1 (order keys by normalized
identifier). Because the ordering key for the key-versions array is the *normalized* identifier, the
normalization must be applied before the sort; otherwise the sort operates on the raw identifier and
may produce a different order than the normalized identifier would (Section A.2). This is why the
recommended pipeline order (Section 6.6) places the normalize stage before the order stage. An
implementer who normalizes during serialization — after ordering — will sort on the raw identifier and
may misorder the keys. The interaction is a sequencing constraint: normalize the key identifier first,
then sort on the normalized form. No other normalization has this constraint, because no other
normalized field is a sort key: algorithms, subjects, and principals are normalized but are not sort
keys, so their normalization may be applied at any point before serialization without affecting order.

### V.2 Selection before ordering

The second interaction is between selection (Section 7 revocation exclusion, Section 8 version folding)
and ordering (Section 9). The ordering of the grants array applies to the *surviving* grants — the sort
operates on the collection after revocation exclusion, not before (Section 9.2 says "after the revoked
grants have been excluded"). Similarly, the ordering of versions applies to the *folded* versions — the
sort operates on all versions, because all are folded (Section 9.4). An implementer who orders before
selecting would sort a collection that includes elements that will then be removed, which is wasteful but
not incorrect for revocation (removing elements from a sorted collection leaves it sorted) — however it
is a source of confusion and the recommended order selects first. For folding the sequencing does not
matter because folding keeps all versions, so ordering the full set is the same whether done before or
after the (no-op) fold; but conceptually the fold gathers and the order sorts, and the order sorts what
the fold gathered.

### V.3 Ordering of object members versus array elements

The third interaction, treated in Section 5.4 and Section 9.5, is between the two kinds of ordering:
object-member ordering (Rule A-1) and array-element ordering (Rules E-1 through E-4). They are
orthogonal — one orders members by key name, the other orders elements by content — and both are
required. An implementer who applies one and not the other produces a manifest that is canonical in one
dimension and not the other, and its digest mismatches. The interaction to understand is that they do
not substitute for each other: sorting object keys does nothing to array element order, and sorting
array elements does nothing to object key order. A conforming manifest satisfies both simultaneously,
which in the recommended structure (Appendix E) is achieved by the order stage (array elements) and the
serialize stage (object members) together.

### V.4 Record count and version folding

The fourth interaction is between the record count (Rule D-2) and version folding (Rule D-1). The record
count is the total folded versions, so it is consistent with the folded key-versions array only if
folding is complete: a manifest that folds all versions and reports the true total is consistent, while
a manifest that drops versions and reports the reduced count is consistent-but-wrong (both the array and
the count reflect the defect), and a manifest that folds all versions but reports the reduced count (or
vice versa) is internally inconsistent, which is a distinct error. The verification (Section F.3) checks
both the array and the count, so it catches all three failure modes. An implementer should compute the
record count from the same folded collection that populates the key-versions array, so that the two are
consistent by construction.

### V.5 Normalization and the migration tables

The fifth interaction is between normalization (Section 6) and the migration tables (Section 10). The
tables receive normalized data (the same normalized data that feeds the manifest), except that the
custody-grants table retains all grants including revoked ones (Section 10.3). An implementer should be
clear that the *normalization* applies to both the manifest and the tables (the tables store lower-cased
key identifiers, upper-cased algorithms, and so on), while the *selection* (revocation exclusion) applies
only to the manifest, not to the custody-grants table. Conflating the two — applying revocation exclusion
to the table, or omitting normalization from the table — produces a table inconsistent with its intended
role as the working mirror.

---

## Appendix W — Lessons learned

This appendix records the lessons the bank drew from the incident, as required by the undertaking's
documentation obligation. The lessons are organizational and process lessons; they do not add rules, but
they explain the controls the bank adopted to prevent recurrence, which is part of the remediation.

### W.1 Byte-level verification catches what content verification misses

The canonicalization defect (Section G.3) went undetected for years because the bank's own tooling
compared statement content, never statement bytes. The lesson is that a reproducibility requirement
(SUP-CUST-7.3) must be verified at the byte level, because a content-only comparison cannot detect a
byte-level divergence that preserves content. The bank adopted byte-level digest comparison as a standing
control, so that any future divergence in serialization is caught immediately rather than only when an
external party introduces a byte-level check.

### W.2 Cross-checking the statement against the archive catches selection defects

The revocation and version-folding defects (Sections G.1, G.2) went undetected because the statement's
readers did not cross-check the statement's selections against the archive's records. The lesson is that a
statement derived from an archive must be periodically cross-checked against that archive, so that a
selection defect — a grant that should have been excluded but was not, a version that should have been
folded but was not — is caught. The bank adopted a standing cross-check of the statement against the
archive as part of its quarterly reconciliation, which is the control that caught the defects and now
runs routinely.

### W.3 Explicit rules prevent plausible-sounding misunderstandings

Each of the three defects arose from a plausible-sounding misunderstanding of the statement's nature
(Section G.4): copy-every-grant, reduce-to-latest, preserve-insertion-order are each reasonable for some
kind of document and wrong for this one. The lesson is that the rules governing a regulated statement must
be stated explicitly and in full, rather than left to be inferred from the statement's name or purpose,
because a plausible-sounding inference can be wrong. This report is the bank's response to that lesson: it
states the rules explicitly, with rationale and worked examples, so that an implementer does not have to
infer them.

### W.4 Separation of production and verification is essential

The defects were caught by an independent verifier (the supervisor), not by the producer (the bank). The
lesson, reflected in the governance of Appendix T, is that the party that produces a regulated statement
should not be the sole party that verifies it; an independent verifier, recomputing the statement from the
source, is what catches producer defects. The bank reaffirmed the separation of production and verification
and the prohibition on embedding the expected digest (which would let the producer's output pass verification
without genuine computation), so that independent verification remains meaningful.

### W.5 Historical retention is a first-class requirement

The version-folding defect reflected a failure to treat historical retention as a first-class requirement:
the pipeline treated the statement as a current-state view, dropping history. The lesson is that in an escrow
context, where the whole purpose is to keep historical material recoverable, historical retention is not an
afterthought but a primary requirement, and the statement must reflect all held material, not just current
material. The bank reaffirmed that the escrow programme's statements are all-material statements, and the
version-folding rule (Section 8) encodes that.

---

## Appendix X — Per-field verification worksheet

This appendix provides a worksheet an implementer can use to verify each field of the produced manifest
against the rules, field by field. The worksheet is organized by manifest member. For each member it states
what to check. An implementer who confirms every item produces a conforming manifest.

### X.1 Top-level members

Confirm the top-level object opens with `custodyGrants` and that the six members appear in the order
`custodyGrants`, `generatedFrom`, `keyVersions`, `manifestVersion`, `recordCount`, `wrappingCertificates`.
Confirm there is no seventh member (no `jurisdiction`, no `revoked` marker at the top level). Confirm
`manifestVersion` is the integer 1. Confirm `generatedFrom` is the export's identifier string, verbatim.
Confirm `recordCount` is the integer equal to the total folded versions across all keys.

### X.2 custodyGrants members

Confirm the array contains only non-revoked grants (no revoked identifiers). Confirm the grants are ordered
by grant identifier, ascending. For each grant confirm the members appear as `certId`, `grantId`,
`grantedEpoch`, `principal`, in that order. Confirm `certId` is lower-cased. Confirm `grantId` is verbatim.
Confirm `grantedEpoch` is the verbatim integer epoch. Confirm `principal` is trimmed of surrounding
whitespace with internal whitespace preserved. Confirm there is no `revoked` member on the grant (it would be
uniformly false and is not carried).

### X.3 keyVersions members

Confirm the array is ordered by normalized (lower-cased) key identifier, ascending. For each key confirm the
members appear as `keyId`, `versions`, in that order, and that `keyId` is lower-cased. Confirm the `versions`
array contains *every* version of the key (compare its length against the export), ordered by integer version
ascending. For each version confirm the members appear as `algorithm`, `createdEpoch`, `custodyStatus`,
`version`, in that order. Confirm `algorithm` is upper-cased. Confirm `createdEpoch` is the verbatim integer
epoch. Confirm `custodyStatus` is verbatim. Confirm `version` is the verbatim integer.

### X.4 wrappingCertificates members

Confirm the array is ordered by certificate identifier, ascending. For each certificate confirm the members
appear as `certId`, `keyId`, `notAfterEpoch`, `subject`, in that order. Confirm `certId` is verbatim. Confirm
`keyId` is lower-cased. Confirm `notAfterEpoch` is the verbatim integer epoch. Confirm `subject` is trimmed and
has internal whitespace runs collapsed to single spaces, with no other reformatting of the distinguished name.

### X.5 The digest

Having confirmed every field, compute the manifest's SHA-256 digest and compare against the expected value in
the checksum artifact. A match confirms the manifest is byte-identical to the reference and that every rule was
applied. A mismatch, after every field-level item above has been confirmed, indicates a serialization-style
difference (indentation, line endings) rather than a content or ordering difference; confirm the serialization
style matches the reference (pretty-printed with sorted keys; Section C.10).

---

## Appendix Y — Key lifecycle and rotation policy

This appendix describes the key lifecycle and rotation policy of the escrow programme in more detail than
the background section, because understanding the lifecycle clarifies why versions accumulate and why every
version matters. It is background; it does not add rules to the manifest.

### Y.1 The lifecycle states

An escrowed key version moves through a lifecycle of states, recorded in the version's custody status. A
version is *active* while it is the current version used to protect newly-written records. When the key is
rotated, the active version becomes *retired*: it no longer protects new records, but it still protects the
records written while it was active, and those records remain recoverable only with the retired version's
material. A retired version is never discarded, because discarding it would render its records unrecoverable.
The custody status thus distinguishes the single active version of a key from its retired predecessors, but,
crucially, both active and retired versions are held and both appear in the manifest (Section 8.5): the
status is descriptive, not selective.

### Y.2 The rotation schedule

Keys are rotated on a schedule determined by the key's cryptographic type and the sensitivity of the records
it protects. A symmetric key protecting bulk records might rotate annually; an asymmetric key used for
wrapping might rotate on a longer schedule tied to the certificate lifecycle. Each rotation produces a new
version with the next version number, so a key's version count reflects its age and rotation frequency: a
long-lived, frequently-rotated key accumulates many versions, each protecting the records of its active
period. The accompanying export's `kms-root-zeta`, with three versions, reflects two rotations over its
lifetime; `kms-root-mu`, with one version, has not yet rotated.

### Y.3 Why retired versions cannot be discarded

The temptation to discard retired versions — the temptation that produced the version-folding defect — comes
from an analogy with systems where rotation supersedes the old key entirely, such as a signing key where old
signatures are re-verified against a published historical key but the old private key is destroyed. In an
escrow context the analogy fails: the escrow archive holds the material precisely so that the records the
old version protected can be recovered, and recovery requires the old private material, which therefore
cannot be destroyed. The retired version is not a superseded artifact to be cleaned up; it is retained
material with a continuing recovery function. This is the crux of Section 8.2 and Section B.4, restated here
in lifecycle terms: retirement is a change of role (from protecting-new to protecting-only-historical), not a
removal, and the manifest reflects all held material regardless of role.

### Y.4 The relationship between key versions and wrapping certificates

A key's versions and its wrapping certificates rotate on independent schedules. A single wrapping certificate
may wrap several versions of a key over the certificate's lifetime, and a single key version may be wrapped
under successive certificates as certificates rotate. In the current archive each key is bound to one wrapping
certificate at export time (Section 1.1), but the general relationship is many-to-many across time. The
manifest records the key's versions (in `keyVersions`) and the wrapping certificates (in `wrappingCertificates`)
as separate collections, linked by the key identifier, rather than nesting certificates under versions, because
the many-to-many relationship does not nest cleanly and because the two collections answer different questions
(what material is held, versus what certificates protect it).

### Y.5 Custody over the lifecycle

Custody, conferred by grants, is over wrapping certificates rather than over key versions directly (Section
1.2): a custodian holds a certificate's private key and can thereby unwrap whatever key material that
certificate wraps. As certificates rotate, custody grants are re-issued against the new certificates, and the
grants against retired certificates may be revoked. The manifest's surviving grants therefore reflect current
custody over current certificates, while the migration table's full grant set (including revoked grants against
retired certificates) reflects the custody history. This is why the manifest excludes revoked grants (current
custody) while the table retains them (custody history), a distinction the lifecycle makes natural.

---

## Appendix Z — Industry context and closing observations

This appendix places the remediation in its industry context and offers closing observations. It is
background and commentary; it does not add rules.

### Z.1 Escrow in the regulated-custody industry

Key escrow of the kind this programme implements is a recognized function in the regulated-custody industry,
where institutions must be able to recover client material under legally compelled circumstances while
maintaining strict controls over who can effect a recovery. The tension the industry manages is between
recoverability (the material must be recoverable, so it and its historical versions must be held) and
control (recovery must be tightly authorized, so custody must be precisely stated and promptly withdrawn when
revoked). The custody manifest sits at exactly this tension: its version folding serves recoverability (all
held material is enumerated) and its revocation exclusion serves control (only current custody is stated). A
manifest that got either wrong would fail one side of the tension, which is why both rules are essential.

### Z.2 Canonicalization as an industry practice

Canonical serialization for reproducible verification, the practice the ordering and normalization rules
implement, is well-established in the industry wherever a document must be independently verifiable by hash —
in certificate transparency, in signed software manifests, in reproducible builds. The practice is always the
same: fix the byte layout so that the same content always produces the same bytes, so that a hash comparison
is a meaningful content comparison. The escrow programme's adoption of canonical serialization for the custody
manifest brings it into line with this established practice, and the canonicalization defect (which predated
the adoption) was simply the absence of a practice the industry had already established elsewhere.

### Z.3 The value of explicit specification

The overarching observation of this remediation is the value of explicit specification. Each of the three
defects was an implicit assumption — about grants, about versions, about serialization — that turned out to be
wrong, and each went undetected because the assumption was never written down to be checked. The remediation's
central act is to write the rules down, explicitly and in full, with rationale and examples, so that they can
be checked, implemented, and verified without inference. This report is long not for its own sake but because
the rules, their rationale, their interactions, and their verification genuinely require this much to state
without leaving room for the kind of plausible-sounding misunderstanding that produced the defects. An
implementer who reads it in full will understand not only what the rules are but why, and will be far less
likely to reintroduce a defect than one who worked from the rules' names alone.

### Z.4 Closing

The corrected migration pipeline, implementing the rules of this report, produces a canonical custody manifest
that excludes revoked grants, folds every key version, and serializes in canonical order with normalized
values, and whose SHA-256 digest matches the supervisor's independently computed expected value. The pipeline
preserves the pre-existing service routes, derives the manifest from the export and the rules without embedding
the expected digest, and writes the migration tables as the working mirror. The verification suite of Section
11 and the reconciliation of Appendix R confirm the manifest's conformance. With this report and the corrected
pipeline, the bank discharges the undertaking of Section 2.2 and brings the escrow archive's custody statement
into a regulator-acceptable, reproducible, and correct state.

The three defects that occasioned this remediation — the inclusion of revoked grants, the dropping of
historical key versions, and the non-canonical serialization — are each, in retrospect, a small and localized
error with a large and consequential effect on a regulated statement. Each is correspondingly small and
localized in its scope. But the discipline that this report
represents — stating the rules explicitly, verifying them independently, and cross-checking the statement
against the archive — is what turns three small corrections into a durable assurance that the custody manifest
means what it says. That assurance, and not merely the corrected bytes, is the object of the remediation.

*This concludes report KE-REM-2024-0417 and all its appendices.*

---

## Appendix AA — Examination question bank

This appendix records the examination questions the supervisor posed to the engineering team to
confirm that the team understood the rules deeply enough to implement them without reintroducing a
defect. Each question is recorded with the answer the team gave and the supervisor accepted. The
questions probe the rules from angles the body does not, and reading them is a good way for a new
implementer to confirm their own understanding.

### AA.1 If the export lists a key's versions in descending order, what order does the manifest use?

Ascending. The export's ordering is not significant (Section 4.1); the pipeline sorts each key's
folded versions by integer version, ascending (Rule E-4), regardless of the order they appeared in the
export. A descending export order is sorted to ascending; an already-ascending export order is left
ascending; a scrambled export order is sorted to ascending. The manifest's version order is a property
of the pipeline's sort, not of the export.

### AA.2 If two grants have the same principal but different grant identifiers, and one is revoked, what appears?

Only the non-revoked grant. Revocation is decided per grant on the grant's own flag (Rule C-1, Section
7.3); the fact that another grant shares the principal is irrelevant. The non-revoked grant survives and
appears (ordered by its grant identifier among the survivors); the revoked grant is excluded. The
principal appears once, via the surviving grant. If both grants were non-revoked, both would appear, as
two separate grant entries with the same principal but different grant identifiers.

### AA.3 If a key has versions 1, 2, and 4 (version 3 missing from the export), what does the manifest show?

Versions 1, 2, and 4, in that order, and a contribution of three to the record count. The pipeline folds
the versions that exist and orders them ascending (Rule D-1, Rule E-4); it does not synthesize the
missing version 3 (Section 9.4 notes that gaps in the archive are preserved, not filled). The manifest
faithfully reflects that the archive holds versions 1, 2, and 4 for this key. A missing version is an
archive fact, not a pipeline error, and the pipeline does not attempt to repair it.

### AA.4 If a certificate subject is entirely whitespace, what does the manifest show?

An empty string. Rule B-2 trims and collapses whitespace; a subject that is entirely whitespace trims to
an empty string. This is a degenerate case that indicates a malformed certificate in the archive, but the
pipeline's normalization handles it deterministically (producing an empty string) rather than erroring, so
that the manifest can be produced and the malformed certificate surfaced at reconciliation. The
accompanying export has no such subject.

### AA.5 If the export has no custody grants at all, what does the custodyGrants array contain?

An empty array. With no grants, there are no survivors, and the array is empty. This is a legitimate
manifest (a set of escrowed keys and certificates with no current custody grants), and it serializes as an
empty `custodyGrants` array in its canonical top-level position. The record count is unaffected by the
absence of grants (Section 7.5); it still counts the folded key versions.

### AA.6 Does changing a version's custody status from active to retired change the manifest's structure?

It changes the value of that version's `custodyStatus` member (carried verbatim), but it does not change
whether the version is folded (every version is folded regardless of status; Section 8.5) or the version's
position (ordered by integer version, not by status; Rule E-4). So the manifest's structure — which
versions appear and in what order — is unchanged; only the one status value changes. The digest changes
because a value changed, but no version appears or disappears.

### AA.7 If two implementations disagree only on indentation, do their digests match?

No. Indentation is part of the serialized bytes, and the digest is over the bytes (Section C.10). Two
implementations that produce the same content and member ordering but different indentation produce
different bytes and different digests. This is why the serialization style (pretty-printed with sorted
keys) must be agreed and matched, not only the content and ordering. An implementer whose content and
ordering are correct but whose digest mismatches should check indentation and line-ending conventions
against the reference.

### AA.8 If a grant references a certificate whose key has been dropped by the version-folding defect, is the grant affected?

The grant's survival is not affected — it depends only on the grant's revocation flag (Section 7.3) — so a
non-revoked grant survives regardless of what the version-folding defect did to the key. But this question
illustrates the independence of the defects: the version-folding defect corrupts the key-versions array
without touching the grants, and the revocation defect corrupts the grants without touching the key-versions
array. A pipeline with the folding defect but not the revocation defect produces correct grants and
incorrect key versions; the grant referencing the affected key's certificate is itself correct even though
the key's version list is wrong. The defects are localized to different parts of the manifest.

### AA.9 What is the record count if every key has exactly one version?

The number of keys, because each key contributes one folded version. This is the one case where the correct
record count (total folded versions) coincides with the number of keys, and it is why a verification that
used only single-version keys could not distinguish a correct pipeline from one with the version-folding
defect (Section C.1, Section F.3): both produce a record count equal to the key count when every key has one
version. The verification uses a multi-version key precisely so that the correct count (six) differs from the
key count (three), making the defect detectable.

### AA.10 If the pipeline is run twice on the same export, are the two manifests identical?

Yes. The pipeline is deterministic and idempotent (Section I.7): given the same export and the same rules, it
produces the same manifest, byte-for-byte, on every run. There is no randomness, no dependence on wall-clock
time, and no dependence on prior runs (the table population clears and rewrites). Two runs produce identical
manifests with identical digests. Determinism is essential to reproducibility: a non-deterministic pipeline
could not produce a manifest whose digest a verifier could independently reproduce.

---

## Appendix AB — Alternative export shapes

This appendix examines export shapes other than the accompanying sample, to confirm that the rules produce a
well-defined manifest for each. The shapes are hypothetical illustrations; the accompanying export is the one
the supervised replay uses. Examining alternative shapes confirms that the rules are complete — that they
define a manifest for any well-formed export, not only for the sample.

### AB.1 A single key with many versions

Consider an export with one key having ten versions, no certificates, and no grants. The manifest's
`keyVersions` array has one key entry whose `versions` array has all ten versions in ascending order (Rule
D-1, Rule E-4); its `custodyGrants` and `wrappingCertificates` arrays are empty; its `recordCount` is ten.
The single key's identifier is lower-cased (Rule B-1) and, being the only key, is trivially ordered. This
shape exercises version folding at scale and confirms that folding is not limited to a small number of
versions.

### AB.2 Many keys with one version each

Consider an export with fifty keys, each with one version, and assorted certificates and grants. The
`keyVersions` array has fifty entries, ordered by normalized key identifier, each with a one-element
`versions` array; the `recordCount` is fifty (fifty keys times one version). This shape exercises key
ordering at scale and confirms that ordering by normalized identifier scales, and it is the shape where the
record count coincides with the key count (Section AA.9).

### AB.3 All grants revoked

Consider an export whose grants are all revoked. The `custodyGrants` array is empty (all grants excluded by
Rule C-1), while the certificates those grants referenced still appear in `wrappingCertificates` (Section
7.4) and the keys still appear in `keyVersions`. This shape exercises the revocation exclusion at its
extreme and confirms that a manifest with no surviving grants is well-defined: it records the keys and
certificates the archive holds, with no current custody.

### AB.4 Certificates with no keys

Consider an export with a certificate whose referenced key is not present (a dangling certificate reference,
the certificate analogue of Section C.3). The certificate still appears in `wrappingCertificates` with its
lower-cased (dangling) key reference, because certificates are carried on their own terms; the missing key
simply does not appear in `keyVersions`. This shape indicates archive inconsistency and would be surfaced at
reconciliation, but the pipeline produces a well-defined manifest for it rather than erroring, so that the
inconsistency is visible in the manifest.

### AB.5 An export identical to the sample but with two keys' identifiers swapped in case

Consider the sample export modified so that `KMS-ROOT-Alpha` is recorded as `kms-root-alpha` and
`KMS-ROOT-Zeta` as `KMS-ROOT-ZETA`. Because Rule B-1 lower-cases all key identifiers, the normalized
identifiers are unchanged (`kms-root-alpha`, `kms-root-zeta`), and therefore the ordering (Rule E-1) and the
entire manifest are unchanged: the manifest is identical to the sample's, byte-for-byte, and has the same
digest. This shape confirms that normalization erases the incidental case variation exactly as intended:
two exports that differ only in the case of their key identifiers produce identical manifests, which is the
point of normalization (Section B.2). An implementer whose manifest differed between these two exports would
have failed to normalize the key identifiers.

### AB.6 An export with the sample's content but its arrays reversed

Consider the sample export with its `escrowedKeys`, `wrappingCertificates`, and `custodyGrants` arrays each
reversed, and each key's `versions` array reversed. Because the pipeline imposes its own ordering (Section 9)
regardless of the export's order, the manifest is identical to the sample's, byte-for-byte, with the same
digest. This shape confirms that the export's ordering is not significant and that the pipeline's ordering is
what determines the manifest (Section 4.1, Section C.9). An implementer whose manifest differed between the
sample and its reversed form would have failed to impose the canonical ordering and would instead be
inheriting the export's order.

---

## Appendix AC — Testing strategy and continuous verification

This appendix describes the testing strategy the engineering team adopted for the corrected pipeline and the
continuous verification the bank runs to prevent regression. It is process context; it describes how the rules
are tested, not new rules.

### AC.1 Unit-level rule tests

Each rule is tested in isolation against the sample export at the unit level, so that a regression in any one
rule is caught by a specific failing test. The canonical-ordering rule is tested by serializing the manifest and
asserting that the top-level members, and a representative nested object's members, appear in lexicographic
order. The revocation rule is tested by serializing the manifest and asserting that the revoked grant identifiers
are absent and the survivors present, with exactly the expected number of survivors. The version-folding rule is
tested by serializing the manifest and asserting that a multi-version key's versions all appear in ascending
order and that the record count equals the total folded count. These unit tests express the correct behaviour;
they fail on a pipeline exhibiting the corresponding defect and pass on a corrected pipeline, which is exactly
the debugging signal the remediation needs. A pipeline under repair fails the tests for the defects it still
exhibits and passes them as each defect is corrected.

### AC.2 Endpoint-level tests

The HTTP surface is tested end to end: the health probe is asserted to respond, and the replay endpoint is
asserted to return success, emit the manifest file, and report the expected record count. The endpoint tests
start the service on an ephemeral port so that they do not collide with a service bound to the standard port,
and they clean up the emitted manifest after each test so that tests do not interfere with one another. The
endpoint tests confirm that the service is wired correctly and that the replay path runs end to end, and the
record-count assertion ties the endpoint test to the version-folding rule.

### AC.3 The digest test as an integration check

Beyond the rule and endpoint tests, an integration check computes the emitted manifest's digest and compares it
against the expected digest artifact. This check is the closest analogue in the test suite to the supervisor's
reconciliation, and it passes only when every rule is satisfied and the serialization style matches. The digest
check is the strongest single assertion — a passing digest implies a byte-identical manifest — but it is opaque
(a failure does not say which rule was violated), which is why the rule-level tests accompany it: the rule-level
tests localize a failure that the digest check only detects.

### AC.4 Determinism in the tests

The tests rely on the pipeline's determinism (Section AA.10): the same export produces the same manifest, so the
tests can assert exact expected values (exact grant identifiers, exact version orders, exact record counts, an
exact digest). A non-deterministic pipeline could not be tested this way, because its output would vary between
runs. The tests therefore also serve as a determinism check: if a test that asserts an exact value passes on one
run and fails on another with the same input, the pipeline has a non-determinism the tests will surface.

### AC.5 Continuous verification

The bank runs the test suite continuously against the pipeline, so that any change that reintroduces a defect is
caught before it reaches production. The continuous verification includes the rule-level tests, the endpoint
tests, and the digest check, and it runs on every change to the pipeline. This is the standing control that
prevents the defects from recurring at the code level, complementing the quarterly reconciliation that would
catch them at the data level (Appendix W.2). Between the two controls — continuous code-level testing and
periodic data-level reconciliation — a reintroduced defect is caught either immediately (by the tests) or at the
next reconciliation (by the cross-check against the archive), and the bank considers the combination sufficient
assurance against recurrence.

### AC.6 Offline operation of the tests

The test suite operates entirely offline: it reads the sample export from the local assets, exercises the
in-process pipeline, and asserts against local expected values, with no network access at any point. This is
required because the supervised replay environment has no network access (the build and run are offline), so the
tests must run in the same offline conditions. An implementer confirms that the tests resolve their dependencies
from the local build cache and reach no external service, so that the test run in the offline environment matches
the test run during development.

---

## Appendix AD — Implementation checklist

This appendix is a compact checklist an implementer works through to bring a pipeline into conformance. It
restates, in imperative form, the confirmations of the per-field worksheet (Appendix X) and the consolidated
rules (Appendix U), organized as a sequence of implementation tasks. Working through it in order produces a
conforming pipeline.

1. Read the export from the working-directory-relative assets path into the data model, without transformation.
2. Lower-case every key identifier, including the key references on certificates, in the parsed data.
3. Upper-case every version's algorithm in the parsed data.
4. Trim every grant's principal; do not collapse its internal whitespace.
5. Trim and collapse internal whitespace in every certificate subject.
6. Filter the grants to retain only those whose revocation flag is false.
7. For each key, fold all of its versions (do not reduce to the latest).
8. Sort the surviving grants by grant identifier, ascending.
9. Sort the keys by normalized key identifier, ascending.
10. Sort each key's folded versions by integer version, ascending.
11. Sort the certificates by certificate identifier, ascending.
12. Compute the record count as the total folded versions across all keys.
13. Build the manifest object with the six top-level members and the nested structure.
14. Configure the serializer to order every object's members by key name (do not rely on insertion order).
15. Serialize with the agreed pretty-printed style and write to the working-directory manifest path.
16. Write the migration tables: all folded versions to key_versions, all certificates to wrapping_certificates,
    and all grants (including revoked) to custody_grants.
17. Do not embed or reference the expected digest anywhere; derive the manifest from the export and the rules.
18. Run the test suite; confirm every rule-level test, the endpoint tests, and the digest check pass.

An implementer who completes every step produces a pipeline that emits a canonical custody manifest conforming
to every rule of this report, whose digest matches the supervisor's expected value, and which passes the
verification and the reconciliation. This is the end state the remediation defines, and the checklist is the
shortest path to it.

---

## Appendix AE — Extended incident chronology with intermediate correspondence

The incident timeline of Section 3 gives the load-bearing dates; the correspondence log of Appendix D
summarizes the material exchanges. This appendix reconstructs the intermediate chronology in finer
granularity, filling in the dated working-level events between the milestone exchanges, so that a reader
reconstructing the incident has the full sequence rather than only its inflection points. The events below
are drawn from the bank's incident record and the supervisor's correspondence file; each is dated to the day
and attributed to the party that recorded it. Nothing in this appendix changes any rule; it is a fuller
record of how the rules came to be settled.

### AE.1 The reconciliation window that opened the incident

**2023-03-16.** The supervisor issued the quarterly custody-statement request under its standing programme,
naming the reporting period and the archive whose export it required. The request was routed to the custody
engineering team's intake queue and acknowledged the same day. The team scheduled the export extraction for
the following working day.

**2023-03-17.** The custody engineering team ran the platform's export extraction against the production
archive, producing the nested JSON dump that became the input to the reconciliation. The extraction ran to
completion without error and produced an export whose identifier the team recorded in the extraction log. The
team then ran the then-current statement-generation pipeline against the export, producing the custody
statement the bank filed.

**2023-03-20.** The bank filed the custody statement and the underlying export with the supervisor, closing
the request within the four-working-day service level the standing programme specifies. The filing was
acknowledged by the supervisor's intake the same day. At this point neither party was aware of any defect;
the filing was routine.

**2023-03-27.** The supervisor's reconciliation analyst began the independent recomputation. The analyst
loaded the export into the supervisor's reference tooling and produced an independent statement, intending to
compare it against the bank's filed statement in the ordinary way. The reconciliation was scheduled to
complete within the supervisor's own two-week window.

**2023-03-31.** The analyst completed the independent recomputation and ran the comparison. The comparison
flagged a population discrepancy: the bank's filed statement enumerated more principals than the analyst's
recomputation. The analyst escalated the discrepancy to the supervisory reviewer, who opened an incident
file and directed that the discrepancy be characterized before it was raised with the bank.

### AE.2 Characterizing the first discrepancy

**2023-04-01.** The analyst characterized the population discrepancy by identifying the specific principals
present in the bank's statement but absent from the recomputation. There were two: a principal corresponding
to a custodian who had left the programme, and a principal corresponding to a decommissioned automated
service identity. The analyst cross-referenced both against the archive's grant records and found that both
grants carried the revocation flag in the archive. The recomputation had honoured the flag and excluded the
grants; the bank's statement had not. This confirmed the discrepancy as a selection defect rather than a
data-entry error, and the reviewer raised it with the bank the next working day.

**2023-04-02.** The supervisor raised the discrepancy with the bank in the exchange logged in Appendix D. The
bank's custody engineering team opened its own incident file, assigned the investigation, and began tracing
the statement-generation code path that emitted the grants. The team's initial hypothesis, later confirmed,
was that the emission loop copied every grant without consulting the revocation flag.

**2023-04-03.** The engineering team traced the emission loop and confirmed the hypothesis: the loop iterated
over the archive's grant collection and emitted a statement entry for each grant, with no conditional on the
revocation flag. The team recorded the code location and the confirmed root cause in its incident file and
began preparing the response the bank filed on 2023-04-05.

**2023-04-05.** The bank filed its initial response, acknowledging the defect and describing its root cause,
as summarized in Appendix D. The response did not yet propose a remediation; it committed to propose one
within the week, which became the annotation proposal of 2023-04-11.

### AE.3 The annotation detour

**2023-04-11.** The bank proposed the annotation remediation (carry every grant with a revocation marker),
logged in Appendix D. The proposal was routed to the supervisory reviewer for assessment. The reviewer
consulted the revocation standard SUP-CUST-11 and the definition of a current-custody statement before
replying.

**2023-04-14.** The reviewer circulated an internal assessment of the annotation proposal within the
supervisor's office, concluding that annotation did not satisfy the completeness requirement of SUP-CUST-11
because a marked-but-listed revoked grantee is still enumerated among the statement's principals. The
assessment recommended directing exclusion rather than annotation, and it drafted the reasoning that became
Section 7.2 and Section B.3.

**2023-04-18.** The reviewer replied to the bank rejecting the annotation proposal and directing exclusion,
as logged in Appendix D. The reply also introduced, for the first time, the distinction between the manifest
(which excludes revoked grants) and a working record (which may retain them), foreshadowing the migration-
table rule of Section 10.3.

**2023-04-25.** The bank accepted exclusion and raised the grant-less-certificate question logged in Appendix
D. The engineering team had recognized, while designing the exclusion filter, that excluding a grant could
leave its referenced certificate with no surviving grant, and it wanted the supervisor's direction on whether
such a certificate should also be dropped.

**2023-04-28.** The reviewer replied that a grant-less certificate is carried on its own terms, as logged in
Appendix D and codified in Section 7.4. The reply reasoned that a certificate's existence and expiry are facts
the statement records regardless of grants, and that dropping a certificate because its grants were revoked
would conflate two independent facts (the certificate's existence and the survival of grants against it).

### AE.4 The version-folding discovery and its resolution

**2023-05-08.** With the revocation defect understood, the supervisory reviewer directed the analyst to
examine the statement's treatment of key versions, on the reasoning that a pipeline with one selection defect
might have others. The analyst began comparing the statement's per-key version coverage against the archive's
version records.

**2023-05-11.** The analyst completed the version comparison and found that the statement listed a single
version per key — always the highest-numbered — even for keys the archive recorded with several versions. The
reviewer raised this with the bank the same day, as logged in Appendix D, characterizing it as a break in the
audit trail required by REC-AUDIT-4.

**2023-05-16.** The bank filed its response identifying the reduce-to-latest reconciliation step as the root
cause, as logged in Appendix D. The engineering team's incident file recorded the code location of the
reduction and the reasoning (that the author had treated the statement as a current-state view) that Section
G.2 later expanded.

**2023-05-23.** The reviewer rejected the reduce-to-latest assumption at length and directed the fold-all
remediation, as logged in Appendix D and codified in Section 8. The direction also specified ascending version
order (Section 9.4) and the total-folded-versions record count (Section 8.4), settling two questions the fold
raised.

**2023-06-02.** The bank raised the deduplication question logged in Appendix D, having recognized that two
versions of a key could share an algorithm and custody status and wondering whether such versions should be
merged. The engineering team wanted to avoid over-folding as much as under-folding.

**2023-06-07.** The reviewer replied that versions are not deduplicated, as logged in Appendix D and codified
in Section 8.3. The reply reasoned that each version is a distinct rotation protecting a distinct (possibly
overlapping) set of records, and that merging would drop a version and reintroduce the defect under
remediation.

### AE.5 The canonicalization discovery and its resolution

**2023-07-10.** The supervisor's office began building an independent reference implementation to recompute
statement digests, motivated by the reproducibility requirement of SUP-CUST-7.3 and by the recognition that a
content-only comparison had missed nothing yet but could miss a byte-level divergence. The reference
implementation was to serialize the statement canonically and hash the result.

**2023-07-20.** The reference implementation's digest disagreed with the bank's on content both parties agreed
was correct, as logged in Appendix D. Investigation showed the divergence was in object-member ordering: the
bank's serializer preserved insertion order, the reference sorted keys. The reviewer raised the byte-level
reproducibility requirement with the bank.

**2023-08-01.** The bank proposed the maintained-logical-order remediation logged in Appendix D. The proposal
reflected the engineering team's instinct to fix the ordering by agreeing an order rather than by configuring
the serializer, and it introduced the idea of a shared order table.

**2023-08-08.** The reviewer rejected the maintained-table approach and directed lexicographic order applied
by serializer configuration, as logged in Appendix D and codified in Section 5. The reply's reasoning — that a
maintained table is a source of drift, whereas lexicographic order is self-describing — became Section B.1.

### AE.6 The normalization questions and the undertaking

**2023-08-15 through 2023-09-12.** The bank raised the series of normalization questions logged in Appendix D,
and the reviewer answered them over the following weeks, producing the rules of Section 6: key identifiers
lower-cased, algorithms upper-cased, subjects whitespace-collapsed, principals trimmed but not internally
collapsed. Each answer emphasized the minimum-transformation principle and warned against over-normalization.

**2023-09-01.** The bank gave the written undertaking, as logged in Appendix D and described in Section 2.2.
The undertaking committed the bank to replay the archive through a corrected pipeline, produce a verifiable
canonical manifest, and document the rules — the obligation this report discharges.

**2023-09-06.** The reviewer acknowledged the undertaking and added the no-embedded-digest requirement, as
logged in Appendix D. The addition reflected the reviewer's concern that a pipeline could pass the digest check
by asserting the answer rather than computing it, which would defeat the purpose of the supervised replay.

**2023-09-13.** The bank confirmed its pipeline would derive the manifest from the export and the rules with no
embedded digest, closing the undertaking's terms.

### AE.7 Drafting and finalization

**2023-09-19 through 2024-02-28.** The bank and supervisor iterated on the wording of the rules, resolving the
raw-versus-normalized ordering question (2023-09-26), the integer-versus-string version ordering question
(2023-10-10), and the successive drafting refinements logged in Appendix D. Drafts KE-REM-2024-0392 and
KE-REM-2024-0405 date from this period and are superseded by this report.

**2024-03-14.** The supervisor confirmed the reference implementation's digest on the sample export and
directed that the checksum artifact carry that value once regenerated from the reference solution, as logged
in Appendix D. The confirmation reiterated that the value must come from the reference implementation's output
and must not be embedded in the pipeline under test.

**2024-04-17.** The report was finalized and the supervised replay scheduled, as recorded in Section 3 and
Appendix D. The three defects were adopted as the defects the corrected pipeline must not exhibit, and the
verification of Section 11 was adopted as the acceptance criterion.

---

## Appendix AF — Deeper regulatory citation discussion

Appendix H introduced the four supervisory instruments the escrow programme operates under. This appendix
discusses each instrument in more depth, tracing the specific paragraphs that bear on the manifest and
explaining how the rules of this report satisfy them at the level of the paragraph rather than the instrument.
The discussion is a summary of the instruments as they bear on the manifest; the instruments themselves govern
in case of any discrepancy, and nothing in this appendix creates an obligation beyond those the instruments and
the body of this report already establish.

### AF.1 SUP-CUST-7, paragraph by paragraph

SUP-CUST-7 opens with a statement of purpose: a custodian of cryptographic key material must be able to
demonstrate, on demand, that its control over that material is what the custodian represents it to be. The
custody statement is the instrument of that demonstration, and the paragraphs that follow specify what the
statement must contain and how it must be produced.

Paragraph 7.1 requires that the statement enumerate every piece of key material the custodian holds. In the
escrow context, "every piece" includes every version of every key, because each version is a distinct piece of
material protecting a distinct set of records (Appendix Y.3). The version-folding rule (Section 8) implements
paragraph 7.1 by folding every version; a statement that dropped versions would enumerate fewer pieces than the
custodian holds and would violate 7.1.

Paragraph 7.2 requires that the statement represent each holding accurately, which the report interprets as the
fidelity requirement: each value in the statement must match the archive's record of that value, up to canonical
normalization of incidental formatting. The normalization rules (Section 6) implement 7.2 by canonicalizing
incidental formatting (case, whitespace) while preserving significant content, so that the statement's values
faithfully represent the archive's while being reproducible.

Paragraph 7.3, discussed in AF.2, sharpens accuracy into reproducibility. Paragraph 7.4 requires that the
statement be producible "on demand," which the report interprets as an operational requirement satisfied by the
replay pipeline and its runbook (Appendix I): the pipeline produces the statement from the export whenever the
supervisor draws an export, so the statement is available on demand rather than only on a schedule.

### AF.2 SUP-CUST-7.3, the reproducibility paragraph

SUP-CUST-7.3 is the single most consequential paragraph for the manifest's byte layout. It requires that two
parties computing the statement from the same underlying archive arrive at byte-identical results. The paragraph
is explicit that the identity required is byte-level, not content-level: it speaks of "identical results," and
the supervisor's practice (Appendix R) interprets this as identity of the serialized bytes, verified by a digest
comparison.

The paragraph's rationale, spelled out in its explanatory note, is that the supervisor's verification is a
recompute-and-compare: the supervisor does not trust the custodian's statement on its face but recomputes it
independently and compares. For the comparison to distinguish a real difference in custody posture from a mere
difference in computation, the two computations must, on identical input and rules, produce identical output.
Byte-level identity guarantees this; content-level identity does not, because two content-identical statements
can differ in bytes (the canonicalization defect, Section G.3) and a content-level comparison would then report
no difference where a byte-level comparison reports one, or a byte-level comparison would report a difference
that a content-level comparison would call spurious. The paragraph resolves the ambiguity by requiring byte-level
identity, which the canonicalization rules (Sections 5 and 6) deliver.

The paragraph does not itself specify the canonical form; it requires only that a canonical form exist and be
shared. The report's choice of lexicographic member order and the specific normalizations is the report's
discharge of the paragraph: it specifies a canonical form and documents it so that any conforming implementation
produces the same bytes. Paragraph 7.3 is satisfied not by any particular canonical form but by the existence of
a shared, documented one, which Sections 5 and 6 provide.

### AF.3 SUP-CUST-11, the revocation instrument

SUP-CUST-11 governs the withdrawal of access. Its central requirement, in paragraph 11.2, is that when an
authorization is withdrawn, the withdrawal be reflected "promptly and completely" in any statement of current
access. The report's revocation-exclusion rule (Section 7) implements the completeness half of this requirement;
the promptness half is an operational property of the archive's revocation recording (a grant is flagged revoked
as soon as the withdrawal is effected) rather than a property of the manifest, which reflects whatever the
archive records at export time.

The word "completely" is the operative one, discussed at length in Appendix H.3. Paragraph 11.2's explanatory
note clarifies that a withdrawal is reflected completely only if the withdrawn authorization does not appear in
the statement of current access — not as an active entry, and not as a marked entry. The note explicitly
addresses the annotation approach the bank once proposed (Appendix D, 2023-04-11) and rejects it: a marked entry
is still an entry, and its presence invites a reader to count the withdrawn grantee among those with access,
which is an incomplete reflection. The exclusion rule (Section 7.1) is the report's response to this note.

Paragraph 11.3 requires that the withdrawal history remain auditable, which the report satisfies not in the
manifest but in the archive and the migration tables (Section 10.3): the manifest excludes revoked grants, but
the archive retains them and the `custody_grants` migration table mirrors them, so the withdrawal history is
auditable from those sources. The report is careful to separate the current-custody statement (which excludes)
from the audit record (which retains), and paragraph 11.3 is satisfied by the audit record, not the statement.

### AF.4 REC-AUDIT-4, the retention instrument

REC-AUDIT-4 is a recordkeeping standard of general application; its bearing on the manifest is through its
requirement, in paragraph 4.4, that historical states of key material remain auditable. In the escrow context,
"historical states" are the retired key versions, and "auditable" means that an auditor can confirm, from the
custodian's records, that the historical material needed to recover past records is still held.

The version-folding rule (Section 8) implements paragraph 4.4 by ensuring the manifest enumerates every
historical version, so that an auditor can confirm from the manifest that no historical version has been lost.
A statement that listed only current versions would give the auditor no basis to confirm retention of historical
material, and the audit paragraph 4.4 contemplates could not be performed from the statement. The record count
(Section 8.4), by reporting the total folded versions, gives the auditor a single number to reconcile against the
archive's version count, which the auditor FAQ (Appendix K.3) describes as the practical audit procedure.

Paragraph 4.5 of REC-AUDIT-4 requires that the retention be demonstrable across custodian systems, which in the
escrow context means the manifest and the migration tables must agree on the held material: the `key_versions`
table's row count equals the manifest's record count (Section 10.1), so an auditor can cross-check the two and
confirm they reflect the same held material. The report's insistence that the table and the manifest fold the
same versions (Appendix E.4) is the discharge of paragraph 4.5.

### AF.5 The instruments as a system

The four instruments are not independent; they form a system in which SUP-CUST-7 states the accuracy baseline,
SUP-CUST-7.3 sharpens it into reproducibility, SUP-CUST-11 adds the revocation completeness requirement, and
REC-AUDIT-4 adds the historical retention requirement. The manifest satisfies the system as a whole: it is
accurate (SUP-CUST-7), reproducible (SUP-CUST-7.3), complete with respect to revocation (SUP-CUST-11), and
complete with respect to historical retention (REC-AUDIT-4). No rule of this report serves only one instrument;
the normalization rules serve both the fidelity of SUP-CUST-7 and the reproducibility of SUP-CUST-7.3, and the
version-folding rule serves both the completeness of SUP-CUST-7 and the historical retention of REC-AUDIT-4. The
control mapping of Appendix L identifies the primary instrument behind each rule, but the instruments overlap,
and a rule typically serves more than one. An implementer who keeps the whole system in view understands why the
rules are what they are and is less likely to introduce a defect that violates one instrument while satisfying
another.

---

## Appendix AG — Additional worked examples

Appendix A worked the core examples; this appendix adds further worked examples that exercise rule
interactions and edge cases not covered there. Every example is consistent with the accompanying export
(`assets/key-escrow-export.json`) and its data: three escrowed keys (`kms-root-zeta` with three versions,
`kms-root-alpha` with two versions, `kms-root-mu` with one version, six versions in total), three wrapping
certificates, and five custody grants of which `CG-0002` and `CG-0005` are revoked. Where an example
introduces a hypothetical value to illustrate a rule, it says so explicitly and does not contradict the
accompanying data.

### AG.1 Folding `kms-root-alpha`'s two versions

The key recorded in the archive as `KMS-ROOT-Alpha` has two versions in the export. Suppose the export
presents them in the order version 2 first, then version 1:

- version 2, algorithm `aes-256-gcm`, custody status `active`, created at epoch 1706745600;
- version 1, algorithm `aes-256-cbc`, custody status `retired`, created at epoch 1685577600.

Applying the rules: Rule B-1 lower-cases the key identifier to `kms-root-alpha`; Rule D-1 folds both versions
(no version is dropped); Rule E-4 orders them by integer version ascending, so version 1 precedes version 2;
Rule B-4 upper-cases each algorithm; Rule A-1 orders each version object's members as `algorithm`,
`createdEpoch`, `custodyStatus`, `version`, and the key object's members as `keyId`, `versions`. The correct
manifest entry is a key object whose `keyId` is `kms-root-alpha` and whose `versions` array has two elements:
the first version 1 with algorithm `AES-256-CBC`, custody status `retired`, created epoch 1685577600; the
second version 2 with algorithm `AES-256-GCM`, custody status `active`, created epoch 1706745600. A pipeline
exhibiting the version-folding defect would keep only version 2 and produce a one-element array, contributing
one to the record count instead of two.

### AG.2 The single-version key `kms-root-mu`

The key recorded as `KMS-ROOT-Mu` has exactly one version:

- version 1, algorithm `aes-256-gcm`, custody status `active`, created at epoch 1699401600.

Applying the rules: Rule B-1 lower-cases to `kms-root-mu`; Rule D-1 folds the single version; Rule E-4 orders a
one-element array trivially; Rule B-4 upper-cases the algorithm; Rule A-1 orders the members. The correct
manifest entry is a key object whose `keyId` is `kms-root-mu` and whose `versions` array has one element:
version 1 with algorithm `AES-256-GCM`, custody status `active`, created epoch 1699401600. As Section C.1
notes, a pipeline with the version-folding defect produces the *same* one-element array for this key as a
correct pipeline, because reducing a single-version key to its latest drops nothing; the single-version key
therefore does not distinguish the defect, which is why the verification exercises a multi-version key.

### AG.3 The full `keyVersions` array in order

Combining AG.1, AG.2, and the worked `kms-root-zeta` of Section A.1, the full `keyVersions` array for the
accompanying export has three key objects, ordered by normalized identifier ascending: first `kms-root-alpha`
(two versions), then `kms-root-mu` (one version), then `kms-root-zeta` (three versions). The record count is
2 + 1 + 3 = 6 (Rule D-2). Note that the ordering is by the normalized identifier, not the raw one: the raw
identifiers `KMS-ROOT-Alpha`, `KMS-ROOT-Mu`, `KMS-ROOT-Zeta` happen to sort in the same relative order as
their normalized forms in this export (because their distinguishing segments begin with upper-case letters in
alphabetical order), but as Section A.2 explains, that coincidence is not guaranteed in general, and the rule
requires the normalized order regardless.

### AG.4 A surviving grant against a certificate whose other grant was revoked

Consider certificate `WC-001`, referenced by two grants in the accompanying export: the surviving `CG-0001`
and the revoked `CG-0002`. Applying Rule C-1, `CG-0002` is excluded and `CG-0001` survives. The certificate
`WC-001` still appears in `wrappingCertificates` (Section 7.4), because certificates are carried on their own
terms; the surviving grant `CG-0001` still references it. So `WC-001` appears in the manifest with a surviving
grant against it, even though one of its two grants was revoked. This example shows that revoking one grant
against a certificate does not affect either the certificate's presence or the survival of the certificate's
*other* grants: each grant's survival is decided independently on its own revocation flag.

### AG.5 A certificate left with no surviving grant

Consider a hypothetical variation in which certificate `WC-002`'s only grants were both revoked. In the
accompanying export, `WC-002` is referenced by the surviving `CG-0003` and the revoked `CG-0005`, so it does
retain a surviving grant. But suppose, for illustration, that `CG-0003` were also revoked. Then `WC-002` would
have no surviving grant, yet Rule 7.4 requires that it still appear in `wrappingCertificates`, recording its
existence and expiry, with no grant referencing it in `custodyGrants`. This is the grant-less-certificate case
of Appendix K.7: expected and not an error, indicating that the certificate exists but that no principal
currently holds custody against it. (In the actual export this case does not arise, because `CG-0003`
survives; the variation is illustrative only and does not change the accompanying data.)

### AG.6 Trimming versus collapsing on adjacent fields

Consider the surviving grant `CG-0007` with principal `  ops.custodian@custodian-bank.example  ` (surrounding
whitespace) and the certificate `WC-004` with subject `CN=Escrow Wrapping   Authority,  O=Custodian Bank,C=DE`
(internal whitespace runs). Rule B-3 trims the principal to `ops.custodian@custodian-bank.example`, leaving no
internal whitespace to consider (the principal has none). Rule B-2 trims and collapses the subject to
`CN=Escrow Wrapping Authority, O=Custodian Bank,C=DE`, collapsing the three-space run in the common name to one
space and the two-space run after the first comma to one space, while not inserting a space where none exists
(no space is added before `C=DE`). The example shows the two rules applied to adjacent fields on the same
manifest, and it shows the difference: had the principal contained internal whitespace, Rule B-3 would have
preserved it, whereas Rule B-2 collapses the subject's internal whitespace. An implementer who applied the
same transformation to both fields would either collapse internal whitespace in the principal (which B-3
forbids) or merely trim the subject without collapsing (which B-2 forbids).

### AG.7 The provenance and count members together

The `generatedFrom` member is the export's `exportId`, carried verbatim; the `manifestVersion` is the integer
1; the `recordCount` is the integer 6. These three scalar members illustrate the verbatim-carry rule (Section
6.1) for `generatedFrom`, the fixed-constant rule for `manifestVersion`, and the total-folded-versions rule
(Section 8.4) for `recordCount`. A common error is to set `recordCount` to the number of keys (3) rather than
the total versions (6); another is to set it to the number of surviving grants (3) or the number of
certificates (3), both of which coincidentally equal 3 for this export but are the wrong quantity. The record
count counts folded versions, and only folded versions; its value of 6 for this export is the sum 2 + 1 + 3,
and no other quantity in the export happens to equal 6, so a `recordCount` of 6 is a strong (though not
conclusive) signal that the folding was performed correctly. A conclusive check compares the per-key version
array lengths (2, 1, 3) against the record count (6) and against the archive's version records.

### AG.8 Assembling the complete manifest content

Bringing all the pieces together, the complete manifest content for the accompanying export is: a
`custodyGrants` array of three surviving grants (`CG-0001`, `CG-0003`, `CG-0007`, in that order, each with
trimmed principal and lower-cased certificate reference); a `generatedFrom` equal to the export's identifier;
a `keyVersions` array of three keys (`kms-root-alpha` with two versions, `kms-root-mu` with one, `kms-root-zeta`
with three, all folded and version-ordered, all algorithms upper-cased); a `manifestVersion` of 1; a
`recordCount` of 6; and a `wrappingCertificates` array of three certificates (`WC-001`, `WC-002`, `WC-004`, in
that order, each with lower-cased key reference and collapsed subject). Serialized with lexicographic member
order at every level and the agreed pretty-printed style, these bytes are what the digest is computed over. Any
deviation — a member out of order, an un-normalized value, a dropped version, an included revoked grant —
changes the bytes and the digest.

---

## Appendix AH — Deeper per-rule edge-case walkthroughs

This appendix walks each rule through its boundary conditions in more depth than Appendix C, treating the
conditions where a rule is easiest to misapply. It is organized by rule set. Each walkthrough restates the
rule, then works the boundary condition, then states the correct outcome and the incorrect outcome an
implementer might produce. Nothing here changes a rule; each walkthrough is an application of a rule already
stated in the body.

### AH.1 Rule A-1 at the deepest nesting level

Rule A-1 (Section 5) requires lexicographic member order at every object level, including the deepest. The
deepest object in the manifest is a version object, nested inside a key object's `versions` array, which is
itself an element of the top-level `keyVersions` array. A version object's four members — `algorithm`,
`createdEpoch`, `custodyStatus`, `version` — must appear in that lexicographic order. The boundary condition
is that these members are easy to overlook when configuring a serializer, because they are two levels deep and
an implementer verifying the top-level order may not descend to check them. The correct outcome is that every
version object, at every depth, has its four members in lexicographic order; the incorrect outcome is a
manifest that emits the top level in lexicographic order but leaves the deep objects in some other order,
producing a manifest that is canonical at the top and non-canonical deep inside. The requirement (Section 5.3)
is that every object's members are in lexicographic order uniformly at every depth, so that depth does not
matter.

### AH.2 Rule B-1 on a key reference carried by a certificate

Rule B-1 (Section 6.2) lower-cases every key identifier "wherever it appears," which includes the `keyId`
carried on a wrapping certificate (the reference to the key the certificate protects). The boundary condition
is that a certificate's `keyId` is a reference, not the key's own identifier, and an implementer normalizing
"the key's identifier" might normalize the key object's `keyId` and forget the certificate's `keyId`
reference. The correct outcome is that both the key object's `keyId` and every certificate's `keyId` reference
are lower-cased, so that the reference matches the key it refers to. The incorrect outcome is a manifest in
which the key object's `keyId` is `kms-root-zeta` (normalized) but a certificate referring to it carries
`KMS-ROOT-Zeta` (un-normalized), a mismatch that breaks the linkage and changes the bytes.

### AH.3 Rule B-2 when the subject has no internal whitespace to collapse

Rule B-2 (Section 6.3) trims and collapses internal whitespace runs in certificate subjects. The boundary
condition is a subject that already has canonical spacing — no leading or trailing whitespace and no internal
runs longer than one space. Applying Rule B-2 to such a subject is a no-op: trimming removes nothing (there is
no surrounding whitespace) and collapsing changes nothing (there are no runs longer than one space). The
correct outcome is that the already-canonical subject is unchanged; the incorrect outcome would be an
implementer who, believing the rule must "do something," inserts or removes spaces, changing an
already-correct subject. The rule collapses existing runs and trims existing surrounding whitespace; where
there is nothing to collapse or trim, it does nothing.

### AH.4 Rule B-3 when a principal has no surrounding whitespace

Rule B-3 (Section 6.4) trims surrounding whitespace from principals and preserves internal whitespace. The
boundary condition is a principal with no surrounding whitespace, such as `primary.custodian@custodian-bank.example`
(the principal of `CG-0001`). Applying Rule B-3 is a no-op: there is no surrounding whitespace to trim. The
correct outcome is that the principal is unchanged; the incorrect outcome would be an implementer who
over-applies the rule by lower-casing or collapsing, which Rule B-3 does not authorize. The rule trims only;
where there is no surrounding whitespace, it does nothing.

### AH.5 Rule B-4 on an algorithm already in upper case

Rule B-4 (Section 6.5) upper-cases version algorithms. The boundary condition is an algorithm the archive
happens to record in upper case already. The archive convention is lower case (Section 6.5), so this is
unusual, but the rule handles it: upper-casing an already-upper-case string is a no-op. The correct outcome is
that the algorithm is upper-cased (whether or not it already was); the incorrect outcome would be an
implementer who conditionally upper-cases only lower-case algorithms and leaves mixed-case ones alone, which
could produce a mixed-case algorithm if one appeared. The rule upper-cases unconditionally, so the outcome is
upper case regardless of the input's case.

### AH.6 Rule C-1 on a grant whose certificate has expired

Rule C-1 (Section 7.1) excludes a grant if and only if its revocation flag is true. The boundary condition is
a non-revoked grant against an expired certificate. Section C.4 and Section 7.3 are explicit that expiry does
not remove a grant: the correct outcome is that the non-revoked grant survives into the manifest, and the
expired certificate appears in `wrappingCertificates` with its past `notAfterEpoch`. The incorrect outcome
would be an implementer who infers revocation from expiry and drops the grant, which Rule 7.3 forbids. Grant
survival depends on the revocation flag alone.

### AH.7 Rule C-1 on a revoked grant issued recently

The mirror boundary condition is a revoked grant issued recently — its `grantedEpoch` is close to the export
time. Section 7.3 is explicit that a grant's age does not affect its survival: the correct outcome is that the
recently-issued revoked grant is excluded, exactly as an older revoked grant would be. The incorrect outcome
would be an implementer who reasons "this grant was issued yesterday, surely the revocation is a mistake" and
carries it; the rule does not authorize second-guessing the flag. A revoked grant is excluded regardless of
when it was issued.

### AH.8 Rule D-1 on a key with a gap in its version numbers

Rule D-1 (Section 8.1) folds every version present in the export. The boundary condition is a key whose
version numbers have a gap — say versions 1, 2, and 4, with no version 3 (perhaps version 3 was never created,
or its record is absent). Section 9.4 is explicit: the pipeline orders the versions that exist and does not
synthesize missing ones. The correct outcome is a `versions` array with three elements (versions 1, 2, 4, in
that order), preserving the gap; the incorrect outcome would be an implementer who "fills in" version 3 with a
synthesized entry, which would add material the archive does not hold. The pipeline folds what exists and
orders it; it does not invent. (The accompanying export has no such gap; the walkthrough is illustrative.)

### AH.9 Rule D-2 when a key has many versions

Rule D-2 (Section 8.4) sets the record count to the total folded versions across all keys. The boundary
condition is a key with many versions, which contributes its full version count to the total. The correct
outcome is that the record count sums the version counts of all keys (2 + 1 + 3 = 6 for the accompanying
export); the incorrect outcome would be a record count equal to the number of keys, which arises when the
version-folding defect keeps one version per key. The record count is a total, not a key count.

### AH.10 Rule E-1 when two keys share a prefix

Rule E-1 (Section 9.1) orders keys by normalized identifier, ascending, by code-point comparison. The boundary
condition is two keys sharing a prefix, such as `kms-root-alpha` and `kms-root-alphabet` (hypothetical). Code-
point comparison orders the shorter before the longer when the shorter is a prefix of the longer, so
`kms-root-alpha` precedes `kms-root-alphabet`. The correct outcome follows the standard ascending string
comparison; the incorrect outcome would arise from a comparison that does not handle prefixes correctly, which
a standard comparison does. The accompanying export's three keys (`kms-root-alpha`, `kms-root-mu`,
`kms-root-zeta`) share no prefix beyond `kms-root-`, and their distinguishing segments (`alpha`, `mu`, `zeta`)
order unambiguously.

### AH.11 Rule E-2 and the zero-padded grant identifiers

Rule E-2 (Section 9.2) orders surviving grants by grant identifier, ascending, by string comparison, relying
on the zero-padding to make string order coincide with numeric order. The boundary condition is that the
padding must be consistent: `CG-0001` through `CG-0007` are all four-digit zero-padded, so string comparison
orders them numerically. If a grant identifier were not zero-padded — say `CG-7` instead of `CG-0007` — string
comparison could misorder it (`CG-10` would precede `CG-7` as strings). The correct outcome relies on the
archive's consistent zero-padding, which the accompanying export honours; the pipeline sorts as strings and
gets numeric order for free. The rule does not parse the numeric suffix, so it depends on the padding being
consistent, which the archive guarantees.

### AH.12 Rule E-4 and integer versus string comparison

Rule E-4 (Section 9.4) orders versions by integer comparison, not string comparison. The boundary condition is
a key with a version number that would misorder under string comparison, such as versions 2 and 10: string
comparison places `10` before `2` (because `1` precedes `2` in code-point order), whereas integer comparison
places 2 before 10. The correct outcome is integer order (2 before 10); the incorrect outcome is string order
(10 before 2). The accompanying export's keys have version numbers 1, 2, 3 (for `kms-root-zeta`), 1, 2 (for
`kms-root-alpha`), and 1 (for `kms-root-mu`), all single-digit, so string and integer order coincide for this
export — but the rule requires integer comparison so that a key with a version 10 would order correctly, and
an implementer must use integer comparison even though the coincidence would hide the difference on this
particular export.

---

## Appendix AI — Audit findings register

This appendix records the findings the internal audit function raised during its review of the remediation
(Appendix T.4), together with the bank's response to each and the finding's disposition. The findings are
audit findings about the remediation process and controls, not defects in the manifest; they are recorded
because the undertaking (Section 2.2) requires the bank to document that internal audit reviewed the
remediation and that its findings were addressed. Each finding is identified by a reference, dated, rated for
severity, and given a disposition.

### AI.1 Finding IA-2023-11 — absence of byte-level verification before the incident

**Raised 2023-10-04. Severity: high. Disposition: remediated.** Internal audit found that, before the 2023
incident, the bank had no control that compared custody-statement bytes against an independent recomputation;
its only verification compared statement content. This is the control gap that let the canonicalization defect
(Section G.3) persist undetected. The bank's response was to adopt byte-level digest comparison as a standing
control (Appendix W.1), so that a future serialization divergence is caught by the bank's own tooling rather
than only by an external party. Internal audit confirmed the control was implemented and rated the finding
remediated. The finding is retained in the register as evidence that the control gap was identified and closed.

### AI.2 Finding IA-2023-12 — statement not cross-checked against the archive

**Raised 2023-10-04. Severity: high. Disposition: remediated.** Internal audit found that the pre-incident
statement was not periodically cross-checked against the archive's records, which is why the revocation and
version-folding defects (Sections G.1, G.2) persisted. The bank's response was to adopt a standing
cross-check of the statement against the archive as part of the quarterly reconciliation (Appendix W.2).
Internal audit confirmed the cross-check was scheduled and had run at least once against a live archive, and
rated the finding remediated.

### AI.3 Finding IA-2023-13 — selection logic under-specified

**Raised 2023-10-11. Severity: medium. Disposition: remediated.** Internal audit found that the pre-incident
statement-generation code implemented selection logic (which grants to emit, which versions to keep) that was
not specified in any document the developer could consult, so the developer inferred it — and inferred it
incorrectly for both revocation and version folding. The bank's response was to produce this report, which
states the selection rules explicitly (Sections 7 and 8) with rationale and worked examples (Appendix W.3).
Internal audit confirmed that the rules were documented and rated the finding remediated, noting that the
explicit documentation reduces the risk of a future developer inferring incorrect selection logic.

### AI.4 Finding IA-2023-14 — no separation between producer and verifier of the digest

**Raised 2023-10-18. Severity: medium. Disposition: remediated.** Internal audit found that, before the
incident, the party that produced the statement was also the only party that verified it, so a producer defect
could pass verification. The bank's response was to reaffirm the separation of production and verification
(Appendix W.4, Appendix T.5) — the engineering team produces the manifest, the supervisory reviewer verifies
it independently, internal audit assures the process — and to adopt the prohibition on embedding the expected
digest (Section 2.2), which prevents the producer from asserting the answer. Internal audit confirmed the
separation and the prohibition and rated the finding remediated.

### AI.5 Finding IA-2024-01 — historical retention not treated as first-class

**Raised 2024-01-10. Severity: medium. Disposition: remediated.** Internal audit found that the pre-incident
pipeline treated the statement as a current-state view, dropping historical versions, because historical
retention had not been treated as a first-class requirement of the escrow programme. The bank's response was
to reaffirm that the programme's statements are all-material statements (Appendix W.5) and to encode this in
the version-folding rule (Section 8). Internal audit confirmed the rule and rated the finding remediated,
noting that the rule's rationale (Section B.4, Appendix Y.3) makes the all-material nature of the statement
explicit for future implementers.

### AI.6 Finding IA-2024-02 — runbook did not require a pre-replay verification pass

**Raised 2024-02-07. Severity: low. Disposition: remediated.** Internal audit found that an early draft of the
operational runbook did not require the engineering team to run the verification suite before the supervised
replay, so a non-conforming pipeline could reach the supervised replay and fail it (a reportable event). The
bank's response was to add the pre-replay verification requirement to the runbook (Appendix I, Section F.5),
so that the team confirms conformance before the supervised replay rather than discovering non-conformance
during it. Internal audit confirmed the runbook requirement and rated the finding remediated.

### AI.7 Finding IA-2024-03 — checksum artifact provenance not documented

**Raised 2024-02-21. Severity: low. Disposition: remediated.** Internal audit found that the process for
generating the expected-checksum artifact was not documented, raising a risk that the artifact could be
generated inconsistently or from the wrong source. The bank's response was to document that the checksum is
regenerated from the reference implementation's output on the sample export (Appendix D, 2024-03-14) and is
not embedded in the pipeline under test (Section 2.2). Internal audit confirmed the documentation and rated
the finding remediated, noting that the documented provenance ties the expected checksum to a reproducible
source rather than to an asserted constant.

### AI.8 Summary of the register

All findings in the register are rated remediated at the date of this report. The findings fall into two
groups: control gaps that let the pre-incident defects persist (IA-2023-11 through IA-2023-14, IA-2024-01),
each closed by adopting a standing control or by documenting a rule; and process gaps in the remediation
itself (IA-2024-02, IA-2024-03), each closed by a documentation or runbook change. No finding remained open at
finalization, which is the state the undertaking's internal-audit-review obligation requires. Internal audit's
overall conclusion, recorded separately in its assurance memorandum, was that the remediation addressed the
root causes of the 2023 defects (Section G.4) rather than only their symptoms, and that the controls adopted
reduce the risk of recurrence.

---

## Appendix AJ — Interview summaries

This appendix summarizes the interviews the investigation (Appendix G) conducted with the personnel involved
in the pre-incident pipeline and its operation, so that the reader understands the human context in which the
defects arose. The interviews are summarized rather than transcribed, and the interviewees are identified by
role rather than name, consistent with the bank's personnel-confidentiality practice. The summaries are
included because the undertaking's documentation obligation requires the bank to record how the defects arose
at the level of the decisions people made, not only at the level of the code they wrote.

### AJ.1 The developer of the grant-emission loop

The developer who wrote the grant-emission loop (the code path that produced the revocation defect) described,
in interview, having implemented the loop from a specification that said "emit each grant" without qualifying
"unless revoked." The developer recalled that the archive's grant records did carry a revocation flag, but
that the specification the developer worked from did not mention it, so the developer emitted every grant. The
developer characterized the omission as a specification gap rather than a coding error: the code did exactly
what the specification said, and the specification did not say to exclude revoked grants. The investigation
accepted this characterization and recorded it as the basis for finding IA-2023-13 (under-specified selection
logic): the remediation's response is to specify the selection explicitly (Section 7), so that a future
developer's specification does mention the revocation flag.

### AJ.2 The author of the version-reconciliation step

The author of the version-reconciliation step (the code path that produced the version-folding defect)
described having faced a design choice: the archive stores each key's versions as a collection, and the
statement had to represent that collection somehow. The author chose to reduce the collection to a single
representative version — the highest-numbered — on the reasoning that the current version was "the one of
interest," an assumption the author described as natural for the kinds of systems the author had previously
worked on (current-state systems where old versions are superseded). The author acknowledged, in hindsight,
not having internalized that an escrow archive is an all-material store where historical versions retain a
recovery function. The investigation recorded this as the basis for finding IA-2024-01 (historical retention
not first-class): the remediation's response is to make the all-material nature explicit (Section 8.2,
Appendix Y.3), so that a future author does not make the current-state assumption.

### AJ.3 The engineer who configured the serializer

The engineer who configured the statement's JSON serializer (whose default insertion-order mode produced the
canonicalization defect) described having used the serializer's default configuration without considering the
member-ordering implications, because the statement's readers at the time consumed it by parsing (which is
order-insensitive) rather than by hashing (which is order-sensitive). The engineer noted that the insertion-
order default was invisible as a defect until the supervisor introduced a byte-level digest comparison, at
which point the ordering became load-bearing. The engineer characterized the defect as a latent consequence of
a reasonable default that became incorrect when the verification method changed. The investigation recorded
this as the basis for finding IA-2023-11 (absence of byte-level verification): the remediation requires that
the manifest be emitted in canonical key order (Section 5, Appendix E.1) and verified at the byte level
(Appendix W.1).

### AJ.4 The reconciliation analyst

The supervisor's reconciliation analyst who first detected the discrepancies described the reconciliation
procedure (Appendix R) and the sequence in which the defects surfaced: the population discrepancy (revocation)
first, because it appeared as a straightforward count difference; the version discrepancy next, because the
reviewer directed a version comparison after the first finding; and the byte-level discrepancy
(canonicalization) last, because it required an independent reference implementation to detect. The analyst
emphasized that the reconciliation control worked as intended — it caught all three defects — but that the
byte-level discrepancy would not have surfaced without the independent reference implementation, which the
supervisor built specifically to exercise the reproducibility requirement of SUP-CUST-7.3. The investigation
recorded the analyst's account as confirmation that the controls functioned and that the byte-level check was
the essential addition.

### AJ.5 The supervisory reviewer

The supervisory reviewer who directed the remediation described the reasoning behind rejecting the bank's two
intermediate proposals (annotation of revoked grants, a maintained logical member order). On annotation, the
reviewer explained the completeness requirement of SUP-CUST-11: a statement of current access must not list a
withdrawn grantee at all, because listing one — even marked — invites a miscount. On the maintained order, the
reviewer explained the drift risk: a table both parties maintain is a source of divergence, whereas
lexicographic order is self-describing and requires no maintained table. The reviewer characterized both
rejections as choosing the option that removes a source of error rather than the option that adds
information or readability, on the principle that a reproducible regulated statement is optimized for
verifiability, not for information density or human convenience. The investigation recorded the reviewer's
reasoning as the basis for Sections 7.2, B.3 (annotation) and 5.2, B.1 (member order).

### AJ.6 Common themes across the interviews

The interviews shared a common theme, consistent with the investigation's conclusion in Section G.4: each
defect arose from a choice that was reasonable for a different kind of system or a different verification
method, and became incorrect for a reproducible, all-material, current-custody statement verified at the byte
level. The grant-emission developer's every-grant choice was reasonable for a data dump; the version author's
reduce-to-latest choice was reasonable for a current-state view; the serializer engineer's insertion-order
default was reasonable for a parse-consumed document. None of the interviewees made a careless error; each made
a reasonable choice whose context had shifted. The remediation's response — explicit rules, byte-level
verification, and the all-material framing — is designed to make the correct choice explicit so that a future
implementer does not have to infer it from a context that may have shifted.

---

## Appendix AK — Remediation-verification procedures

This appendix documents the step-by-step procedures the bank uses to verify that a produced manifest conforms
to the rules, at a level of operational detail beyond the verification design of Appendix F. Where Appendix F
describes what each check asserts, this appendix describes how the engineering team runs the checks, what
inputs each requires, what output each produces, and how a failing check is triaged. The procedures are the
bank's operational practice; they mirror the supervisor's reconciliation (Appendix R) so that a manifest that
passes the bank's procedures passes the supervisor's reconciliation.

### AK.1 Procedure V-1: canonical-ordering verification

The engineering team runs the canonical-ordering verification by parsing the produced manifest with a parser
that preserves member order (so that the order in the parsed representation reflects the order in the bytes)
and asserting that the top-level members appear in the lexicographic order `custodyGrants`, `generatedFrom`,
`keyVersions`, `manifestVersion`, `recordCount`, `wrappingCertificates`. The procedure then descends into a
representative nested object — the first grant, the first key, the first version, the first certificate — and
asserts the members of each appear in their lexicographic order (Section 5.3). The input is the produced
manifest; the output is a pass or a report of the first member found out of order. A failure localizes the
problem: a top-level ordering failure indicates the top-level members are not in lexicographic order, while a
deep-object ordering failure indicates only some levels are canonically ordered. The requirement is that every
object's members are in lexicographic order uniformly at every depth (Appendix E.1).

### AK.2 Procedure V-2: revocation verification

The engineering team runs the revocation verification by parsing the produced manifest, collecting the set of
grant identifiers present in `custodyGrants`, and comparing it against the expected set derived from the
export. The procedure asserts that no revoked grant identifier (`CG-0002`, `CG-0005` on the accompanying
export) is present, and that every non-revoked grant identifier (`CG-0001`, `CG-0003`, `CG-0007`) is present,
and that the present set has exactly the expected cardinality (three on the accompanying export). The input is
the produced manifest and the export (to derive the expected set); the output is a pass or a report of the
specific identifiers that were present-but-should-be-absent or absent-but-should-be-present. A failure showing
a revoked identifier present indicates that revoked grants are not being excluded; a failure showing a
survivor absent indicates that a non-revoked grant is being dropped. The requirement is that the manifest
contain exactly the non-revoked grants, excluding revoked ones, per Section 7 and the select-and-fold stage
(Appendix E.1).

### AK.3 Procedure V-3: version-folding verification

The engineering team runs the version-folding verification by parsing the produced manifest, locating a key
known to have multiple versions in the export (`kms-root-zeta`, three versions, on the accompanying export),
and asserting that its `versions` array has the expected number of elements (three) in ascending integer
version order (versions 1, 2, 3). The procedure then asserts that the top-level `recordCount` equals the total
folded versions across all keys (six on the accompanying export) and that the sum of the per-key version-array
lengths equals the record count (a consistency check that catches a record count set correctly but with arrays
that drop versions, or vice versa). The input is the produced manifest and the export (to derive the expected
version counts); the output is a pass or a report of the key whose version count was wrong and the expected
versus actual counts. A failure showing one version per key indicates that only the latest version is
reaching the manifest rather than the full history. The requirement is that every version of every key be
folded into the manifest in ascending version order, per Section 8 and the select-and-fold stage (Appendix
E.1).

### AK.4 Procedure V-4: value-normalization verification

The engineering team runs the value-normalization verification by parsing the produced manifest and asserting,
for each named field, that the normalization was applied: every key identifier (key-object `keyId` and
certificate `keyId` reference) is lower-cased; every version algorithm is upper-cased; every certificate
subject is trimmed with internal whitespace runs collapsed to single spaces; every surviving grant's principal
is trimmed of surrounding whitespace with internal whitespace preserved. The procedure checks each field
against its normalization rule (Section 6) and reports any field whose value does not match the normalized
form. The input is the produced manifest and the export (to derive the expected normalized values); the output
is a pass or a report of the specific field and value that was not normalized. A failure is triaged by
identifying which normalization was missed and adding it to the normalize stage (Appendix E.1), taking care to
apply the key-identifier normalization before the sort (Section 6.6).

### AK.5 Procedure V-5: digest verification

The engineering team runs the digest verification by computing the SHA-256 digest of the produced manifest's
bytes and comparing it against the expected digest in the checksum artifact. The input is the produced manifest
and the checksum artifact; the output is a match or a mismatch. A match, given that V-1 through V-4 pass, is
conclusive: the manifest is byte-identical to the reference. A mismatch, given that V-1 through V-4 pass,
indicates a serialization-style difference (indentation, line endings) rather than a content or ordering
difference, and is triaged by confirming the serialization style matches the reference (pretty-printed with
sorted keys; Section C.10). The digest verification is run last, after the rule-level verifications, because a
digest mismatch is opaque (it does not say which rule was violated), whereas the rule-level verifications point
at the specific defect; running the rule-level verifications first localizes any defect before the digest
verification confirms the overall result.

### AK.6 Procedure V-6: migration-table verification

The engineering team runs the migration-table verification by querying the three migration tables (Section 10)
and asserting: the `key_versions` table's row count equals the manifest's record count (every folded version is
a row); the `wrapping_certificates` table has one row per certificate in the export; and the `custody_grants`
table has one row per grant in the export, including the revoked grants (Section 10.3), so its row count
exceeds the manifest's surviving-grant count by the number of revoked grants (two on the accompanying export,
so five table rows versus three manifest grants). The input is the migration tables and the export; the output
is a pass or a report of the specific table whose row count or contents were unexpected. A failure showing the
`custody_grants` table missing the revoked grants indicates a pipeline that applied the revocation exclusion to
the table as well as the manifest, confusing the working mirror with the current-custody statement (Appendix
E.4); the requirement is that all grants are recorded in the table, with revoked grants excluded only from the
manifest.

### AK.7 Running the procedures in sequence

The engineering team runs the six procedures in the sequence V-1, V-2, V-3, V-4, V-6, V-5: the four rule-level
verifications (V-1 through V-4) first, so that any rule defect is localized; the migration-table verification
(V-6) next, so that any table inconsistency is caught; and the digest verification (V-5) last, so that the
overall byte-level result is confirmed after the rule-level results. A pipeline that passes all six is ready
for the supervised replay (Appendix I). A pipeline that fails any is triaged by the failing procedure's
guidance, corrected, and re-verified; because the pipeline is idempotent (Appendix I.7), re-running it after a
correction is safe. The team does not present a manifest to the supervisor until all six procedures pass,
consistent with the pre-replay verification requirement (finding IA-2024-02, Appendix AI.6).

---

## Appendix AL — Risk register

This appendix records the risks the bank identified in the remediation and its ongoing operation, together with
each risk's likelihood, impact, mitigation, and residual rating. The register is the bank's risk-management
view of the remediation; it does not add rules to the manifest, but it documents the risks the rules and
controls mitigate, which is part of the undertaking's documentation obligation. Each risk is identified by a
reference and rated before and after mitigation.

### AL.1 Risk R-1: recurrence of the revocation defect

**Likelihood before mitigation: medium. Impact: high. Residual after mitigation: low.** The risk that a future
pipeline change reintroduces the revocation defect (carrying revoked grants into the manifest) is mitigated by
the explicit revocation rule (Section 7), the revocation verification procedure (Appendix AK.2), and the
standing cross-check of the statement against the archive (Appendix W.2). The residual risk is low because the
verification procedure would catch a reintroduced defect before the supervised replay, and the cross-check
would catch it in routine operation. The residual is not zero because a pipeline change could, in principle,
disable both the verification and the cross-check, but the governance separation (Appendix T.5) makes such a
change unlikely to pass review.

### AL.2 Risk R-2: recurrence of the version-folding defect

**Likelihood before mitigation: medium. Impact: high. Residual after mitigation: low.** The risk that a future
pipeline change reintroduces the version-folding defect (reducing keys to their latest version) is mitigated by
the explicit folding rule (Section 8), the version-folding verification procedure (Appendix AK.3), and the
all-material framing (Appendix Y.3) that makes the requirement explicit for future implementers. The residual
risk is low for the same reasons as R-1: the verification would catch a reintroduced defect, and the framing
reduces the chance a future implementer makes the current-state assumption. The impact is rated high because a
dropped historical version breaks the audit trail (REC-AUDIT-4) and could, in the worst case, correspond to
material actually lost from the archive rather than merely omitted from the statement — though the manifest
defect is only an omission from the statement, a defect that omitted a version from the statement could mask a
defect that dropped it from the archive, which is why the impact is rated high.

### AL.3 Risk R-3: recurrence of the canonicalization defect

**Likelihood before mitigation: medium. Impact: medium. Residual after mitigation: low.** The risk that a
future pipeline change reintroduces the canonicalization defect (serializing in insertion order) is mitigated
by the explicit ordering rule (Section 5), the canonical-ordering verification procedure (Appendix AK.1), and
the byte-level digest comparison adopted as a standing control (Appendix W.1). The residual risk is low because
the digest comparison catches any byte-level divergence, including an ordering one, immediately. The impact is
rated medium (lower than R-1 and R-2) because the canonicalization defect produces a content-correct manifest —
it misstates neither the access surface nor the recoverable-material surface (Appendix O) — and its consequence
is a failed verification rather than a misrepresentation of the custody posture. It is nonetheless a control
deficiency under SUP-CUST-7.3 and is mitigated accordingly.

### AL.4 Risk R-4: over-normalization corrupting an identity

**Likelihood before mitigation: low. Impact: medium. Residual after mitigation: low.** The risk that a future
pipeline change over-normalizes a principal (lower-casing or collapsing internal whitespace) and thereby
corrupts an identity is mitigated by the explicit trim-only rule for principals (Section 6.4), the warning
against over-normalization (Section B.2, Appendix N.5), and the value-normalization verification procedure
(Appendix AK.4), which would report a principal that differs from its correctly-normalized (trimmed-only) form.
The residual risk is low because the verification would catch an over-normalized principal. The impact is rated
medium because a corrupted identity misrepresents who holds custody, which is a fidelity failure under
SUP-CUST-7, though it is less likely than the selection and ordering defects because the trim-only rule and its
rationale are explicit.

### AL.5 Risk R-5: an embedded digest masking a broken pipeline

**Likelihood before mitigation: low. Impact: high. Residual after mitigation: low.** The risk that a future
pipeline embeds the expected digest (or special-cases the sample export) and thereby passes the digest check
while being incapable of correctly processing a different export is mitigated by the explicit prohibition on
embedding the digest (Section 2.2, Appendix O.4) and by internal audit's review for embedded constants (finding
IA-2024-03, Appendix AI.7). The residual risk is low because a review would detect an embedded digest or a
special case. The impact is rated high because an embedded digest would let a broken pipeline pass the
supervised replay, defeating the replay's purpose (to demonstrate a correct pipeline), which is the security
failure Appendix O.4 describes.

### AL.6 Risk R-6: a mismatch between the manifest and the migration tables

**Likelihood before mitigation: low. Impact: medium. Residual after mitigation: low.** The risk that a future
pipeline treats the manifest and the migration tables inconsistently — for example writing only survivors to
the `custody_grants` table, or folding all versions to the manifest but only the latest to the `key_versions`
table — is mitigated by the explicit table rules (Section 10), the guidance to draw the manifest and the tables
from the correct collections (Appendix E.4), and the migration-table verification procedure (Appendix AK.6),
which cross-checks the table row counts against the manifest. The residual risk is low because the verification
would catch an inconsistency. The impact is rated medium because the tables are a working artifact rather than
the regulated deliverable, so a table inconsistency is a working-store defect rather than a misrepresentation
to the regulator — though a table that disagreed with the manifest would confuse the supervisor's on-site
reconciliation (Appendix R.5), which is why it is mitigated.

### AL.7 Summary of the register

The register records six risks, each rated low residual after mitigation. The three highest-impact risks (R-1,
R-2, R-5) are the recurrence of the two selection defects and the embedded-digest failure, each mitigated by an
explicit rule, a verification procedure, and a standing control or review. The remaining risks (R-3, R-4, R-6)
are the canonicalization recurrence, over-normalization, and table inconsistency, each also mitigated to low
residual. No risk is rated higher than low residual, which reflects the layering of mitigations: an explicit
rule reduces the chance a defect is introduced, a verification procedure catches it before the supervised
replay, and a standing control or review catches it in ongoing operation. The register is reviewed and updated
as part of the bank's ongoing risk management; at finalization all residuals are low.

---

## Appendix AM — Data-lineage notes

This appendix traces the lineage of each manifest field from its origin in the archive, through the export,
through the pipeline's transformations, to its final form in the manifest. The lineage is documented because
the supervisor's reconciliation (Appendix R) may trace a manifest value back to its archive origin, and an
implementer benefits from understanding the full path a value travels. The notes are organized by manifest
field. Each note states the field's archive origin, its export representation, the transformation the pipeline
applies, and its manifest form, and is consistent with the accompanying export's data.

### AM.1 Lineage of `generatedFrom`

The `generatedFrom` member originates as the export's `exportId`, which the platform's export mechanism assigns
when it produces the export (Appendix Q.4). It travels through the export as the top-level `exportId` string,
is read by the parse stage without transformation, is carried through the normalize, select-and-fold, and order
stages unchanged (it is not a normalized field, a selected entity, or an ordered array element), and appears in
the manifest as `generatedFrom`, verbatim. Its lineage is the simplest of any manifest field: origin to
manifest with no transformation. The reconciliation confirms that the manifest's `generatedFrom` matches the
export's `exportId` (Appendix R.1), so that the manifest is reconciled against the export it claims to derive
from.

### AM.2 Lineage of a key's `keyId`

A key's `keyId` originates in the archive as the key's stable identifier, recorded with whatever case the
versioned-era system that first recorded it used (Appendix Q.3), which is why identifiers carry mixed case. It
travels through the export as the escrowed key's `keyId` string (for example `KMS-ROOT-Zeta`). The normalize
stage lower-cases it (Rule B-1) to `kms-root-zeta`, and this normalized form is used as the sort key for the
`keyVersions` array (Rule E-1) and appears in the manifest as the key object's `keyId`. The same normalization
applies to the `keyId` reference carried on any certificate that protects the key (Appendix AH.2), so the
certificate's reference matches the key. The lineage of a `keyId` therefore includes a normalization (lower-
casing) and a use as a sort key, both in the normalize-then-order sequence (Section 6.6).

### AM.3 Lineage of a version's `algorithm`

A version's `algorithm` originates in the archive as the cryptographic algorithm the version used, recorded in
lower case by the cryptographic library that produced the version (Section B.2). It travels through the export
as the version's `algorithm` string (for example `aes-256-gcm`). The normalize stage upper-cases it (Rule B-4)
to `AES-256-GCM`, and it appears in the manifest as the version object's `algorithm`. Its lineage includes one
transformation (upper-casing) and no use as a sort key (the version sort key is the integer version, not the
algorithm). Two versions of a key may share an algorithm; their algorithms have the same lineage but the
versions are not merged (Section 8.3).

### AM.4 Lineage of a version's `custodyStatus`, `createdEpoch`, and `version`

A version's `custodyStatus` originates in the archive as the version's lifecycle state (Appendix Y.1), travels
through the export as a lower-case string, is carried verbatim through the pipeline (it is not normalized;
Section 6.1), and appears in the manifest unchanged. A version's `createdEpoch` originates as the version's
creation time in seconds since the Unix epoch, travels through the export as an integer, is carried verbatim,
and appears in the manifest as the same integer. A version's `version` originates as the version number
assigned at rotation (Appendix Y.2), travels through the export as an integer, is carried verbatim, is used as
the integer sort key for the folded `versions` array (Rule E-4), and appears in the manifest as the same
integer. These three fields have simple lineages: origin to manifest with no transformation, and for `version`,
a use as a sort key.

### AM.5 Lineage of a certificate's `certId`, `keyId` reference, `notAfterEpoch`, and `subject`

A certificate's `certId` originates as the certificate's stable identifier, zero-padded (Section 9.3), travels
through the export as a string, is carried verbatim (the certificate's own identifier is not normalized), is
used as the string sort key for the `wrappingCertificates` array (Rule E-3), and appears in the manifest
unchanged. A certificate's `keyId` reference originates as the identifier of the key the certificate protects,
travels through the export with the same mixed case as the key's own identifier, is lower-cased by the
normalize stage (Rule B-1) so it matches the key, and appears in the manifest lower-cased. A certificate's
`notAfterEpoch` originates as the certificate's expiry in seconds since the Unix epoch, travels through the
export as an integer, is carried verbatim, and appears in the manifest as the same integer (it does not affect
grant survival; Section C.4). A certificate's `subject` originates as the certificate's distinguished name,
recorded with inconsistent internal spacing by whichever certificate authority issued it (Appendix Q.3),
travels through the export as a string, is trimmed and whitespace-collapsed by the normalize stage (Rule B-2),
and appears in the manifest in its collapsed form.

### AM.6 Lineage of a surviving grant's fields

A surviving grant's `grantId` originates as the grant's stable identifier, zero-padded (Section 9.2), travels
through the export as a string, is carried verbatim, is used as the string sort key for the `custodyGrants`
array (Rule E-2), and appears in the manifest unchanged. A grant's `certId` reference originates as the
identifier of the certificate the grant applies to, travels through the export as a string, is carried
verbatim (a `certId` reference is not normalized; only `keyId` references are; Appendix J.5), and appears in
the manifest unchanged. A grant's `grantedEpoch` originates as the grant's issuance time in seconds since the
Unix epoch, travels through the export as an integer, is carried verbatim, and appears in the manifest as the
same integer (a grant's age does not affect survival; Section 7.3). A grant's `principal` originates as the
identity the grant authorizes, sometimes carrying incidental surrounding whitespace introduced during grant
issuance (Appendix Q.3), travels through the export as a string, is trimmed by the normalize stage (Rule B-3,
trim only), and appears in the manifest in its trimmed form. A grant's `revoked` flag originates as the boolean
recording withdrawal, travels through the export as a boolean, governs the grant's survival in the
select-and-fold stage (Rule C-1), and does *not* appear in the manifest (a surviving grant is by definition
not revoked; Appendix J.5) — but it is written to the `custody_grants` migration table (Section 10.3), so its
lineage terminates in the table rather than the manifest.

### AM.7 Lineage of the derived members

Two manifest members are derived rather than carried: `manifestVersion` and `recordCount`. The
`manifestVersion` originates not in the archive but in the manifest schema; it is the fixed integer 1,
introduced by the serialize stage, with no archive lineage. The `recordCount` is derived by the pipeline as the
total folded versions across all keys (Rule D-2); its lineage is a computation over the folded versions rather
than a carry of an archive field. Its value (6 on the accompanying export) is the sum of the per-key version
counts (2 + 1 + 3), which are themselves counts of version objects with the lineages of AM.3 and AM.4. The
record count's lineage is therefore a fold-and-count over the version lineages, and its correctness depends on
the fold being complete (Rule D-1): a dropped version would lower the count below the archive's true total.

### AM.8 The lineage as a whole

Taken together, the lineages show that the manifest is derived from the archive by a small set of well-defined
transformations: verbatim carries (for identifiers, epochs, statuses, and the export identifier),
normalizations (lower-casing key identifiers, upper-casing algorithms, collapsing subjects, trimming
principals), selections (excluding revoked grants, folding all versions), orderings (by normalized key
identifier, grant identifier, certificate identifier, integer version), and derivations (the fixed manifest
version, the computed record count). No manifest field has a lineage that invents data not in the archive, and
no archive field with a manifest destination is dropped except the `revoked` flag (which terminates in the
table) and the `jurisdiction` (which is export metadata not carried; Section C.5). The lineage as a whole is
the record of how the export becomes the manifest, and it is the path the supervisor's reconciliation may
trace when confirming a manifest value against its archive origin.

---

## Appendix AN — Supplementary glossary of cryptographic and process terms

This appendix supplements the glossary of Section 12 and the extended glossary of Appendix P with additional
terms used in the report's process, verification, and lineage discussions, defined at the length those
discussions warrant. The terms are additional to those already defined; a term defined in Section 12 or
Appendix P is not repeated here.

**Reconciliation.** The supervisor's procedure of drawing an export and a produced manifest, independently
recomputing the manifest from the export, and comparing the two (Appendix R). A reconciliation that matches at
the digest level and at the table level is signed off; one that mismatches is diagnosed rule by rule.

**Independent recomputation.** The supervisor's computation of the manifest from the export using an
implementation of the rules that is independent of the bank's pipeline (Appendix R.2). Because the manifest is
canonical, an independent recomputation by a conforming implementation produces byte-identical output, which is
what makes the recompute-and-compare control sound.

**Byte-level verification.** Verification that compares the serialized bytes of two manifests (typically via
their digests) rather than their parsed content (Appendix W.1). Byte-level verification catches divergences
that content-level verification misses, such as the canonicalization defect.

**Content-level verification.** Verification that compares the parsed content of two manifests, insensitive to
byte-level differences such as member ordering. Content-level verification is insufficient for a reproducibility
requirement (SUP-CUST-7.3), because it cannot detect a byte-level divergence that preserves content.

**Select-and-fold stage.** The pipeline stage that applies the two selection rules: excluding revoked grants
(Section 7) and folding all versions of every key (Section 8). Two of the three 2023 defects lived in this
stage (Appendix E.1).

**Normalize stage.** The pipeline stage that applies the value normalizations of Section 6. It is placed before
the order stage because the key-identifier normalization is a sort-key dependency (Section 6.6).

**Order stage.** The pipeline stage that applies the four array orderings of Section 9, operating on the
selected-and-folded data so that it sorts the survivors and the folded versions (Appendix E.1).

**Serialize stage.** The pipeline stage that builds the manifest object and writes it with canonical member
order (Section 5). The canonicalization defect lived in this stage (Appendix E.1).

**Idempotence (of the replay).** The property that re-running the replay against the same export produces the
same manifest and table contents (Appendix I.7). Idempotence is relied on operationally during the
iterate-and-retry cycle of a replay that initially fails verification.

**Access surface.** The set of principals who currently hold custody, enumerated by the manifest's surviving
grants (Appendix O.1). A manifest that overstated the access surface would list revoked grantees; one that
understated it would drop surviving grantees.

**Recoverable-material surface.** The set of records that could be recovered with the key material the archive
holds, bounded by the manifest's folded versions (Appendix O.2). A manifest that understated this surface would
drop historical versions and hide the archive's recovery capability.

**Dangling reference.** A grant whose `certId` references a certificate not present in the export, or any
reference to an absent entity (Section C.3). A dangling reference indicates archive inconsistency; the pipeline
carries a surviving dangling grant with its reference and surfaces the inconsistency at reconciliation rather
than pruning it.

**Grant-less certificate.** A certificate that appears in the manifest but that no surviving grant references,
because its only grants were revoked (Section 7.4, Appendix K.7, Appendix AG.5). A grant-less certificate is
expected and not an error; it indicates the certificate exists but that no principal currently holds custody
against it.

**Zero-padding.** The fixed-width representation of grant and certificate identifiers (`CG-0001`, `WC-001`)
that makes lexicographic string ordering coincide with numeric ordering (Sections 9.2, 9.3), so the pipeline
can sort them as strings without parsing the numeric suffix.

**Fold-all (versus reduce-to-latest).** The correct treatment of a key's versions, gathering the complete
version chain (Section 8), contrasted with the incorrect reduce-to-latest treatment that produced the
version-folding defect (Section G.2). Fold-all keeps every version; reduce-to-latest keeps only the
highest-numbered.

**Exclusion (versus annotation).** The correct treatment of a revoked grant, omitting it from the manifest
entirely (Section 7.1), contrasted with the rejected annotation treatment that would list it with a marker
(Section 7.2, Appendix N.1). Exclusion reflects the withdrawal completely; annotation does not.

**Insertion order (versus lexicographic order).** The order in which a serializer emits object members if left
in its default mode — the order the building code populated them — contrasted with the required lexicographic
order (Section 5). The insertion-order default produced the canonicalization defect (Section G.3).

---

## Appendix AO — Closing observations on maintaining conformance

This closing appendix records observations on maintaining the manifest's conformance over time, after the
supervised replay this report governs has been performed. The observations are forward-looking guidance rather
than rules; they describe how the bank intends to keep future manifests conforming as the archive, the
pipeline, and the supervisory expectations evolve. They are included so that the reader understands the
remediation is not a one-time correction but the establishment of a durable conformance practice.

### AO.1 Conformance is a property to be maintained, not achieved once

The remediation this report specifies brings a single replay into conformance, but the archive continues to
change — keys are rotated, adding versions; grants are issued and revoked; certificates rotate — and each
future export must be replayed into a conforming manifest. Conformance is therefore a property to be maintained
across every future replay, not a state achieved once and left. The bank's standing controls (byte-level
verification, the cross-check of the statement against the archive) exist precisely to maintain conformance
across future replays, catching a divergence whenever one arises rather than only at the initial remediation.

### AO.2 Pipeline changes must be re-verified

Any change to the pipeline — a dependency upgrade, a refactor, a new feature — risks reintroducing one of the
three defects or a new one, because the defects are subtle and a change that seems unrelated can affect
serialization, selection, or ordering. The bank's practice is to run the full verification suite (Appendix AK)
after any pipeline change, before the changed pipeline is used for a supervised replay, so that a reintroduced
defect is caught by the verification rather than by a failed supervised replay. The verification suite is the
regression test for the three defects; a change that passes it has not reintroduced them.

### AO.3 Archive changes may surface new edge cases

As the archive grows, it may present edge cases the accompanying export does not: a key with a version-number
gap (Appendix AH.8), a certificate left grant-less by revocations (Appendix AG.5), a version number reaching
double digits (Appendix AH.12). The rules of this report handle these edge cases (the pipeline folds what
exists without synthesizing, carries grant-less certificates, and orders versions by integer comparison), but
an implementer maintaining the pipeline should confirm, as the archive grows, that the pipeline continues to
handle the edge cases the rules specify. The edge-case walkthroughs of Appendices C, AG, and AH are the
reference for confirming this.

### AO.4 Supervisory expectations may evolve

The supervisory instruments (Appendix AF) may be revised, and a revision could change the rules the manifest
must satisfy. The bank's practice is to track the instruments and to revise this report — and the pipeline —
when a revision bears on the manifest. This report reflects the instruments as they stand at finalization; a
future revision of an instrument would occasion a revision of the report, which would supersede this version as
this version supersedes its drafts. Conformance is to the current instruments, and maintaining conformance
includes tracking their evolution.

### AO.5 The report as a durable specification

This report is written to be a durable specification: it states the rules explicitly, with rationale, worked
examples, and edge-case walkthroughs, so that an implementer maintaining the pipeline over time can consult it
without needing the original authors. The durability is deliberate — the 2023 defects arose in part because the
selection logic was under-specified and had to be inferred (finding IA-2023-13, Appendix AI.3), and a durable
explicit specification is the antidote. An implementer who consults this report has the full rules and their
rationale, and does not have to infer anything, which is the state the remediation exists to establish and to
maintain.

### AO.6 Final observation

The manifest is a small artifact — six top-level members, a handful of nested objects — but its correctness
carries the weight of the bank's custody representation to its regulator, and its byte layout carries the
weight of the reproducibility control that makes that representation verifiable. The rules of this report are
what make the small artifact carry that weight correctly: they fix its content, its selection, its ordering,
and its byte layout so that it says exactly what the custody posture is, no more and no less, in a form any
conforming party can reproduce and verify. The remediation is the establishment of those rules; maintaining
conformance is their continued application. This report is the specification of both.

---

## Appendix AP — Extended examination question bank

Appendix AA established an examination question bank; this appendix extends it with further questions and their
model answers, so that an implementer or reviewer can self-assess understanding of the rules across a wider set
of scenarios. Each question is answered normatively with a reference to the rule it applies; where a question
introduces a hypothetical, the hypothetical is marked and does not contradict the accompanying export's data
(three keys with six versions total, three certificates, five grants of which `CG-0002` and `CG-0005` are
revoked).

### AP.1 Ordering questions

**Q. In what order do the top-level members of the manifest appear, and why that order?**
A. In lexicographic ascending order of member name: `custodyGrants`, `generatedFrom`, `keyVersions`,
`manifestVersion`, `recordCount`, `wrappingCertificates` (Rule A-1, Section 5.2). The order is lexicographic
because lexicographic order is self-describing and requires no maintained convention, which serves the
reproducibility requirement of SUP-CUST-7.3 (Section B.1). A logical order would require a maintained table and
would be a source of drift between the computing and verifying parties.

**Q. A key object and a version object each have their members ordered how?**
A. Each in lexicographic order at its own level (Rule A-1, Section 5.3): a key object as `keyId`, `versions`; a
version object as `algorithm`, `createdEpoch`, `custodyStatus`, `version`. The ordering is recursive, applying
at every object level, not only the top level.

**Q. Are array elements ordered by the same rule as object members?**
A. No. Object members are ordered lexicographically by key name (Section 5); array elements are ordered by the
content key appropriate to the array (Section 9): keys by normalized identifier, grants by grant identifier,
certificates by certificate identifier, versions by integer version. The two orderings are orthogonal and both
are required (Section 5.4, Section 9.5).

**Q. Why are versions ordered by integer comparison rather than string comparison?**
A. So that version 2 precedes version 10 (Rule E-4, Section 9.4). String comparison would place `10` before
`2`, which is wrong. The accompanying export's version numbers are single-digit, so the two comparisons
coincide on this export, but the rule requires integer comparison so that a double-digit version would order
correctly.

### AP.2 Selection questions

**Q. Which grants appear in the manifest, and which do not?**
A. The three non-revoked grants (`CG-0001`, `CG-0003`, `CG-0007`) appear; the two revoked grants (`CG-0002`,
`CG-0005`) do not (Rule C-1, Section 7.1). Revocation is an exclusion, not an annotation: a revoked grant is
absent, not present-with-a-flag (Section 7.2).

**Q. A grant is not revoked but its certificate has expired. Does it appear?**
A. Yes. Grant survival depends only on the revocation flag, not on certificate expiry (Section 7.3, Section
C.4). The non-revoked grant appears, and the expired certificate appears in `wrappingCertificates` with its
past `notAfterEpoch`.

**Q. A grant is revoked but was issued yesterday. Does it appear?**
A. No. A grant's age does not affect its survival (Section 7.3). A revoked grant is excluded regardless of when
it was issued (Appendix AH.7).

**Q. How many versions of a three-version key appear in the manifest?**
A. All three (Rule D-1, Section 8.1). Every version is folded; none is dropped. For the accompanying export's
`kms-root-zeta`, all three versions appear, ordered 1, 2, 3.

**Q. Are two versions of a key that share an algorithm merged?**
A. No. Each version is a distinct rotation distinguished by its version integer, and two versions that share
other attributes are still two versions (Section 8.3). Merging would drop a version and reintroduce the
version-folding defect.

### AP.3 Normalization questions

**Q. How is a mixed-case key identifier represented in the manifest?**
A. Lower-cased (Rule B-1, Section 6.2). `KMS-ROOT-Zeta` becomes `kms-root-zeta`. The lower-casing applies
wherever the identifier appears, including a certificate's `keyId` reference (Appendix AH.2), and precedes the
sort that uses the normalized identifier as its key (Section 6.6).

**Q. How is a lower-case algorithm represented?**
A. Upper-cased (Rule B-4, Section 6.5). `aes-256-gcm` becomes `AES-256-GCM`. The upper-casing applies to every
folded version, not only the active one.

**Q. How is a certificate subject with internal double spaces represented?**
A. Trimmed and with internal whitespace runs collapsed to single spaces (Rule B-2, Section 6.3). The rule
collapses existing runs and does not insert spaces where none exist (Appendix A.4, Appendix AG.6).

**Q. How is a principal with surrounding whitespace represented?**
A. Trimmed of surrounding whitespace, with internal whitespace preserved (Rule B-3, Section 6.4). Unlike
subjects, principals are trimmed only, not internally collapsed, because a principal is an opaque identity
string whose internal characters are significant (Section B.2).

**Q. Is a principal lower-cased?**
A. No. Only trimming applies to principals (Rule B-3). Lower-casing a principal risks corrupting an identity
and is a rejected over-normalization (Appendix N.5, Risk R-4 in Appendix AL.4).

**Q. Are the epoch fields reformatted as dates?**
A. No. The epoch fields (`createdEpoch`, `notAfterEpoch`, `grantedEpoch`) are integers carried verbatim
(Section C.7). The pipeline does not convert them to date strings, change their units, or round them.

### AP.4 Counting and provenance questions

**Q. What is the record count for the accompanying export, and what does it count?**
A. Six (Rule D-2, Section 8.4). It counts the total folded versions across all keys: 2 (for `kms-root-alpha`)
+ 1 (for `kms-root-mu`) + 3 (for `kms-root-zeta`) = 6. It is not the number of keys (3), surviving grants (3),
or certificates (3).

**Q. What is `generatedFrom`, and is it normalized?**
A. It is the export's `exportId`, carried verbatim (Section C.5, Appendix AM.1). It is not normalized; it is a
provenance identifier whose exact form is significant.

**Q. Is `jurisdiction` carried into the manifest?**
A. No. `jurisdiction` is export metadata not carried into the manifest (Section C.5). Adding it would introduce
a seventh top-level member not in the specification, changing the canonical bytes and failing verification.

**Q. What is `manifestVersion`?**
A. The integer 1, the manifest schema version (Section 5.2). It is a fixed constant introduced by the serialize
stage, not carried from the archive (Appendix AM.7).

### AP.5 Verification and process questions

**Q. If a pipeline fixes only the revocation defect, does verification pass?**
A. No (Section C.11). Each rule set is verified independently (Section 11.2). Fixing revocation while leaving
folding and ordering in place passes the revocation check but fails the version-folding and canonical-ordering
checks, and the digest still mismatches. Only fixing all three passes every check and matches the digest.

**Q. Why must the pipeline not embed the expected digest?**
A. Because a pipeline that embeds the digest asserts the answer rather than computing it, so it would pass the
digest check even if its rule logic were broken, defeating the purpose of the supervised replay (Section 2.2,
Appendix O.4). The manifest must be reproducible from the export and the rules by genuine computation.

**Q. Are revoked grants written to any store?**
A. Yes, to the `custody_grants` migration table (Section 10.3), which retains all grants including revoked ones
so the supervisor can audit the revocations. The exclusion of revoked grants is a property of the manifest
only, not of the table (Appendix E.4). The table's row count (5) exceeds the manifest's surviving-grant count
(3) by the number of revoked grants (2).

**Q. In what order does an implementer run the verification procedures?**
A. The four rule-level procedures first (canonical ordering, revocation, version folding, value normalization),
so any defect is localized; the migration-table procedure next; and the digest procedure last, so the overall
byte-level result is confirmed after the rule-level results (Appendix AK.7). A pipeline that passes all six is
ready for the supervised replay.

### AP.6 Interaction questions

**Q. Why must normalization precede ordering?**
A. Because the key-identifier normalization (Rule B-1) produces the sort key for the `keyVersions` array (Rule
E-1); sorting must operate on the normalized form, so normalization must precede the sort (Section 6.6, Appendix
V.1). No other normalized field is a sort key, so no other normalization has this sequencing constraint.

**Q. Does the order of selection and ordering matter?**
A. For grants, the ordering operates on the survivors, so selection (revocation exclusion) precedes ordering
conceptually; ordering before selecting is not incorrect for revocation (removing elements from a sorted
collection leaves it sorted) but the recommended order selects first (Appendix V.2). For versions, folding keeps
all versions, so ordering the full set is the same before or after the fold.

**Q. What is the single most insidious of the three defects, and why?**
A. The canonicalization defect (Section B.1), because it produces a content-correct manifest — every grant,
version, and value is right — that nonetheless fails verification because its bytes are in the wrong order. An
engineer who has confirmed the content is correct may be baffled that the digest still mismatches; the
requirement is always that the manifest's members be emitted in lexicographic key order at every level.

---

## Appendix AQ — Cross-reference index of rules to sections

This appendix provides a cross-reference index tying each normative rule to the sections and appendices that
state it, explain it, work it, or verify it, so that a reviewer tracing a rule can find every place the report
treats it. The index is organized by rule. For each rule it lists the section that states it, the appendix that
explains its rationale, the appendix that works an example of it, and the appendix that describes its
verification. The index restates no rule; it is a navigational aid, and in case of any discrepancy the rule as
stated in the body governs.

### AQ.1 Rule A-1 (canonical key ordering)

Stated in Section 5 (Sections 5.1 through 5.4). Its rationale is in Appendix B.1 (why canonical ordering is a
regulatory requirement) and Appendix H.2 (the reproducibility instrument SUP-CUST-7.3). It is worked in
Appendix A.5 (assembling the top-level object) and Appendix M (the extended serialization walk-through), and
its deepest-level application is walked in Appendix AH.1. Its verification is in Appendix F.1 (the
canonical-ordering check) and Appendix AK.1 (procedure V-1). Its defect signature is in Appendix S.1, its
correspondence origin in Appendix D (2023-07-20, 2023-08-08), its interview context in Appendix AJ.3, and its
risk rating in Appendix AL.3 (Risk R-3).

### AQ.2 Rule B-1 (lower-case key identifiers)

Stated in Section 6.2. Its rationale is in Appendix B.2 (why normalization is separate from ordering). It is
worked in Appendix A.2 (the effect of normalization on ordering) and Appendix AG.3 (the full key-versions
array in order), its reference-carrying edge case in Appendix AH.2, and its lineage in Appendix AM.2. Its
sequencing interaction with ordering is in Section 6.6 and Appendix V.1. Its verification is in Appendix AK.4
(procedure V-4). Its correspondence origin is in Appendix D (2023-08-22, 2023-09-26).

### AQ.3 Rule B-2 (collapse certificate-subject whitespace)

Stated in Section 6.3. Its rationale is in Appendix B.2. It is worked in Appendix A.4 (whitespace collapse
versus trimming) and Appendix AG.6, its no-op edge case in Appendix AH.3, and its lineage in Appendix AM.5. Its
verification is in Appendix AK.4. Its correspondence origin is in Appendix D (2023-09-05).

### AQ.4 Rule B-3 (trim principals)

Stated in Section 6.4. Its rationale is in Appendix B.2, and its contrast with subject collapse in Appendix A.4
and Appendix AG.6. Its no-op edge case is in Appendix AH.4, its lineage in Appendix AM.6, and its rejected
over-normalization alternative in Appendix N.5. Its verification is in Appendix AK.4, its risk rating in
Appendix AL.4 (Risk R-4), and its correspondence origin in Appendix D (2023-09-12).

### AQ.5 Rule B-4 (upper-case algorithms)

Stated in Section 6.5. Its rationale is in Appendix B.2. It is worked in Appendix A.1 (a single key with three
versions) and Appendix AG.1, its already-upper-case edge case in Appendix AH.5, and its lineage in Appendix
AM.3. Its verification is in Appendix AK.4, and its correspondence origin in Appendix D (2023-08-29).

### AQ.6 Rule C-1 (revocation exclusion)

Stated in Section 7 (Sections 7.1 through 7.5). Its rationale is in Appendix B.3 (why exclusion not annotation)
and Appendix H.3 (the revocation instrument SUP-CUST-11). It is worked in Appendix A.3 (revocation exclusion
with surviving grants) and Appendix AG.4, its expired-certificate and recent-grant edge cases in Appendix AH.6
and AH.7, and its lineage (terminating in the table) in Appendix AM.6. Its verification is in Appendix F.2 and
Appendix AK.2 (procedure V-2), its defect signature in Appendix S.2, its rejected annotation alternative in
Appendix N.1, its correspondence origin in Appendix D (2023-04-02 through 2023-04-28), its interview context in
Appendix AJ.1, its audit finding in Appendix AI (IA-2023-13), and its risk rating in Appendix AL.1 (Risk R-1).

### AQ.7 Rule D-1 (complete folding) and Rule D-2 (record count)

Stated in Section 8 (Sections 8.1 through 8.5). Their rationale is in Appendix B.4 (why every version is
folded) and Appendix H.4 (the retention instrument REC-AUDIT-4), with lifecycle context in Appendix Y. They are
worked in Appendix A.1, Appendix AG.1, AG.2, AG.3, and AG.7, their gap edge case in Appendix AH.8 and their
many-version case in Appendix AH.9, and their lineage in Appendix AM.4 and AM.7. Their verification is in
Appendix F.3 and Appendix AK.3 (procedure V-3), their defect signature in Appendix S.3, their rejected
reduce-to-latest alternative in Appendix N.2, their correspondence origin in Appendix D (2023-05-11 through
2023-06-07), their interview context in Appendix AJ.2, their audit finding in Appendix AI (IA-2024-01), and
their risk rating in Appendix AL.2 (Risk R-2).

### AQ.8 Rules E-1 through E-4 (array orderings)

Stated in Section 9 (Sections 9.1 through 9.5). Their rationale is in Appendix B.5 (why the orderings are what
they are) and Appendix H.2 (reproducibility). They are worked in Appendix A.2 (E-1), A.3 (E-2), A.5 (all four),
and AG.3 (E-1), their prefix, zero-padding, and integer-comparison edge cases in Appendix AH.10 through AH.12,
and their lineage (as sort-key uses) throughout Appendix AM. Their verification is in Appendix AK (E-4 in
procedure V-3, the others confirmed by the digest in procedure V-5), and their rejected descending-order
alternative (for E-4) in Appendix N.6.

### AQ.9 The table rule and the provenance and no-embedded-digest rules

The table rule (Section 10.3) is worked in Appendix E.4 and verified in Appendix AK.6 (procedure V-6), with its
risk rating in Appendix AL.6 (Risk R-6). The provenance rule (Section 5.2) is worked in Appendix AG.7 and
AM.1, and its jurisdiction-exclusion aspect in Appendix C.5 and J.1. The no-embedded-digest rule (Section 2.2)
is explained in Appendix E.3 and O.4, its audit finding in Appendix AI (IA-2024-03), and its risk rating in
Appendix AL.5 (Risk R-5).

### AQ.10 Using the index

A reviewer tracing a rule uses this index to find every treatment of the rule: the statement in the body, the
rationale in Appendix B or the relevant regulatory appendix, the worked examples in Appendices A, AG, and the
edge-case walkthroughs in Appendices C, AH, the verification in Appendices F and AK, and the process context in
Appendices D, AI, AJ, AL, and AM. The multiplicity of treatments is deliberate: a rule stated once and never
worked is easy to misread, whereas a rule stated, explained, worked, walked through its edge cases, and
verified is hard to misapply. The index is the map of that multiplicity, so that a reviewer confirming a rule
can confirm it from every angle the report offers.

---

*End of appendices for report KE-REM-2024-0417.*
