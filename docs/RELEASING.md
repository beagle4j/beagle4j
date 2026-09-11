# Releasing

Publishing to Maven Central is automated. Cutting a release is one `git tag`.

Getting to the point where that works needs four one-time setup steps that cannot be
automated, because each one involves an account or a credential.

---

## One-time setup

### 1. A Sonatype Central account

Sign up at **<https://central.sonatype.com>**. Signing in with GitHub is the quickest
route and links the account to the org already.

### 2. Verify the `io.github.beagle4j` namespace

In the portal: **View Namespaces → Add Namespace → `io.github.beagle4j`**.

Sonatype responds with a **verification key** — a random string like `a1b2c3d4e5`. Prove
you control the GitHub organisation by creating a public repository under it with exactly
that name:

```bash
gh repo create beagle4j/<verification-key> --public
```

Back in the portal, press **Verify Namespace**. It usually completes in seconds. The
repository can be deleted afterwards.

### 3. A publishing token

In the portal: **Account → Generate User Token**. It returns a username and a password —
neither is your login. Keep the page open; the password is shown once.

### 4. A GPG signing key

Central requires every artifact to be signed. GPG ships with Git for Windows, so there is
nothing to install.

```bash
gpg --full-generate-key
```

Answer: **RSA and RSA**, **4096** bits, an expiry you are happy with (`2y` is sensible,
`0` for never), your name, your email. **Choose a strong passphrase and keep it** — it
becomes one of the GitHub secrets below, and a key whose passphrase is lost cannot sign
the next release.

Find the key id — the long hex string after `rsa4096/`:

```bash
gpg --list-secret-keys --keyid-format=long
```

Publish the **public** half, so that anyone can verify a release. Central rejects
signatures it cannot check against a keyserver:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

Export the **private** half for CI. This is secret material — it goes straight into the
GitHub secret and nowhere else, least of all into a file inside this repository:

```bash
gpg --armor --export-secret-keys <KEY_ID>
```

### 5. Four GitHub secrets

**Settings → Secrets and variables → Actions → New repository secret**, on
`beagle4j/beagle4j`:

| Secret | Value |
|---|---|
| `CENTRAL_USERNAME` | the token username from step 3 |
| `CENTRAL_PASSWORD` | the token password from step 3 |
| `GPG_PRIVATE_KEY` | the whole `--armor --export-secret-keys` block, `-----BEGIN` line to `-----END` line inclusive |
| `GPG_PASSPHRASE` | the passphrase from step 4 |

---

## Cutting a release

```bash
git tag v0.1.0
git push origin v0.1.0
```

That is the whole procedure. The
[release workflow](../.github/workflows/release.yml) then:

1. rejects the tag if it is not a plain semantic version — a malformed version reaching
   Central is unfixable, since a published version can never be replaced, only superseded;
2. sets the project version from the tag;
3. runs the full test suite, and stops if anything fails;
4. builds the three publishable modules with source and javadoc jars, signs everything,
   and uploads to Central;
5. drafts a GitHub release with generated notes.

The benchmark and sample modules are built and tested but never published. They are a
measuring instrument and a demonstration, and nobody should be able to depend on them.

### The last step is deliberately manual

The upload **validates and then waits**. Log into the portal and press **Publish** to
release it to the world.

This is on purpose. `autoPublish` is set to `false` in the parent pom because a version on
Central is permanent — it cannot be withdrawn, edited, or replaced, only superseded by a
higher version. A held deployment can be dropped; a published one is forever.

Artifacts appear on `repo1.maven.org` within about 30 minutes, and become searchable a few
hours later.

---

## After the first release

Update the install snippets in `README.md` and `README.zh-CN.md` to the published version.
Until the first release lands they point at a version that does not exist, and anyone
following the quick start gets a dependency resolution error.

## Trying it without publishing

To confirm the release build works without touching Central or needing a key:

```bash
mvn -Prelease -DskipTests -Dgpg.skip=true package
```

Each of `beagle-core`, `beagle-jdbc` and `beagle-spring-boot-starter` should produce three
jars — the artifact, `-sources` and `-javadoc`. The benchmark and sample modules should
produce one each.
