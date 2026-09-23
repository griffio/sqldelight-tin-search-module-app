# Postgres 18 with PlanetScale's Lead extension, the local-development stand-in for TIN.
# https://github.com/planetscale/lead
#
# Lead exposes the `tin` extension name, the `tin` access method, the ==> operator, TINQL,
# and the tin.* functions. It scans heap pages instead of maintaining a real index, so it is
# only suitable for small development and test data sets.
#
# Build (takes a while: it compiles Rust + pgrx):
#   docker build -t lead-pg18 docker
# Run:
#   docker run --name lead -e POSTGRES_USER=myuser -e POSTGRES_PASSWORD=mypassword \
#     -e POSTGRES_DB=mydatabase -p 5432:5432 -d lead-pg18

ARG PG_MAJOR=18
ARG LEAD_REF=main
ARG PGRX_VERSION=0.19.1

FROM postgres:${PG_MAJOR} AS builder
ARG PG_MAJOR
ARG LEAD_REF
ARG PGRX_VERSION

RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential clang libclang-dev pkg-config libssl-dev libreadline-dev zlib1g-dev \
      bison flex ca-certificates curl git \
      postgresql-server-dev-${PG_MAJOR} \
    && rm -rf /var/lib/apt/lists/*

ENV RUSTUP_HOME=/usr/local/rustup CARGO_HOME=/usr/local/cargo PATH=/usr/local/cargo/bin:$PATH
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --default-toolchain none

WORKDIR /src
RUN git clone --depth 1 --branch ${LEAD_REF} https://github.com/planetscale/lead.git . \
    && rustup toolchain install "$(sed -n 's/^channel *= *"\(.*\)"/\1/p' rust-toolchain.toml)" --profile minimal \
    && cargo install cargo-pgrx --version ${PGRX_VERSION} --locked \
    && cargo pgrx init --pg${PG_MAJOR} "$(which pg_config)" \
    && cargo pgrx install --release --package tin --no-default-features --features pg${PG_MAJOR}

FROM postgres:${PG_MAJOR}
ARG PG_MAJOR
COPY --from=builder /usr/lib/postgresql/${PG_MAJOR}/lib/tin.so /usr/lib/postgresql/${PG_MAJOR}/lib/tin.so
COPY --from=builder /usr/share/postgresql/${PG_MAJOR}/extension/tin* /usr/share/postgresql/${PG_MAJOR}/extension/
