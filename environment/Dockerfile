# Base: official Maven + Eclipse Temurin JDK 17 (Debian bookworm). Digest pinned
# per the orchestrator's spec — do NOT float the tag.
FROM maven:3.9-eclipse-temurin-17@sha256:1ed5d1f54416b706707b4f3238f63a20bb06aab27c6d240090a2bb9ad895ed45

# ---------------------------------------------------------------------------
# System packages: agent runtime recording tooling (tmux, asciinema) + curl,
# the Python verifier toolchain (python3, pip, venv), and CA certs. This is a
# debug task where the agent edits/rebuilds Java in-place, so the JDK + Maven
# from the base image stay in the final image (no multi-stage). Single apt
# transaction, recommends suppressed, lists cleaned in the same layer.
# asciinema/tmux/curl/python3 tracked to the bookworm security stream; exact
# per-package version pins would break on every point release and offer no
# reproducibility benefit here (base image digest is pinned).
# ---------------------------------------------------------------------------
# hadolint ignore=DL3008
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        asciinema \
        ca-certificates \
        curl \
        python3 \
        python3-pip \
        python3-venv \
        tmux \
    && rm -rf /var/lib/apt/lists/*

# Python verifier deps — baked at build time because task/verify time is
# offline (allow_internet=false) and the verifier runs in this same image
# (environment_mode=shared): `python -m pytest --ctrf` against the Javalin
# endpoints. Debian bookworm marks the system Python env externally-managed,
# so --break-system-packages is required to install into it.
RUN python3 -m pip install --no-cache-dir --break-system-packages \
        pytest==8.4.1 \
        pytest-json-ctrf==0.3.5 \
        requests==2.32.3

WORKDIR /app

# ---------------------------------------------------------------------------
# Maven dependency population (own layer, before source, for build caching).
# COPY the pom.xml first, then go-offline (ONLINE, at build time) to pull every
# dependency + plugin into ~/.m2 so later `mvn -o ...` works with no network.
# ---------------------------------------------------------------------------
COPY server/pom.xml /app/pom.xml
RUN mvn -B dependency:go-offline

# ---------------------------------------------------------------------------
# Project sources + seed data (src/, assets/, docs/, resources, README.md).
# Most volatile layer, goes after the cached dependency layer.
# ---------------------------------------------------------------------------
COPY server/ /app/
RUN mvn test || true

# Build the fat jar offline. The shipped JUnit tests intentionally FAIL
# (planted bugs — this is a debug task), so skip tests during the image build.
# Produces /app/target/key-escrow-service.jar.
RUN mvn -o -B -DskipTests package

# Leave the built project rooted at /app so both the agent and the shared-mode
# verifier operate from the project root where assets/ and docs/ live. Harbor
# drives service startup; a benign default CMD only.
CMD ["bash"]
