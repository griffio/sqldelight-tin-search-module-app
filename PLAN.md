# Plan: SqlDelight module for PlanetScale TIN search

Follows the pattern of `sqldelight-pgsearch-module-app` (closest analog: schema-prefixed
functions, a custom `USING` index method, storage parameters, and a boolean match operator).

Docs: https://planetscale.com/docs/postgres/search
Local dev extension ("Lead", same `tin` extension name): https://github.com/planetscale/lead

## 1. What TIN exposes (from the docs and Lead source)

| Surface | SQL | Type |
| --- | --- | --- |
| Extension | `CREATE EXTENSION IF NOT EXISTS tin;` | |
| Index | `CREATE INDEX x ON t USING tin (col) [WITH (...)]` — one text column/expression per index | |
| Operator | `col ==> 'TINQL string'`, right side may be `:param`, an outer column, or `ANY (ARRAY[...])` | boolean |
| `tin.score(ctid [, dense_ratio, k1, b, term_add, term_replace])` | BM25, requires a `==>` scan in the same query | real |
| `tin.full_score(ctid [, k1, b])` | BM25 without dense-term elision | real |
| `tin.max_score(ctid)` | best score of the scan, constant per row | real |
| `tin.highlight(text [, begin_tag, end_tag, query])` | `<b>..</b>` by default, query implied from `==>` | text |
| `tin.highlight_ansi(text [, wrap_to, query])` | terminal highlighting | text |
| `tin.tokenize(text [, tokenizer, case_folding, ...])` | SETOF text | text rows |
| `tin.score_inspect(regclass, text [, ...])` | SETOF (term text, weight real) | table fn |
| `tin.maybe_quote(text)` | helper | text |
| `ALTER INDEX x SET (k1 = 1.5)` / `RESET (k1)` / `REINDEX INDEX [CONCURRENTLY]` | already in the PostgreSQL dialect grammar | |

Index storage parameters (`WITH (...)`):
`k1`, `b`, `score_stop_words`, `tokenizer`, `case_folding`, `accent_folding`, `long_tokens`,
`max_token_bytes`, `graphemes`, `position_gaps`, `initial_segment_count`, `target_segment_count`,
`max_mutable_segment_size`, `max_merged_segment_size`, `dead_percent_threshold`.

TINQL itself is an opaque string to SqlDelight. It needs no grammar work, only README examples.

## 2. Feasibility checks already done against SqlDelight 2.4.0 / sql-psi

- `ctid` resolves: the PostgreSQL dialect synthesizes `tableoid, xmin, cmin, xmax, cmax, ctid`
  on every table (`CreateTableMixin`), so `tin.score(ctid)` and `tin.score(p.ctid)` compile.
- `USING tin (...)`: `index_method` is an override point (pgsearch overrides it with `'bm25'`).
- `WITH (k1 = 1.2, tokenizer = whitespace)`: `storage_parameters` is an override point; values
  (`numeric_literal`, `identifier`, `string_literal`) are accepted by the base `storage_parameter` rule.
- `ALTER INDEX ... SET/RESET (...)` reuses `storage_parameters`, so the override covers it too.
- `==>` operator: same shape as pgsearch's `'@@@'` / `'==='` literal tokens, added via `extension_expr`.
- `==> ANY (ARRAY[...])`: the right side is `<<expr '-1'>>` and `any_operator_expr` is already an
  expression in the dialect. Expected to parse as-is. Verify in the sample.
- Schema-prefixed functions `tin.score(...)`: `function_name ::= [ 'tin' DOT ] ID`, exactly like `pdb`.
- Named arguments `k1 => 3.2`: NOT in any SqlDelight grammar. Positional calls work today
  (`tin.score(ctid, 0.1, 3.2, 0.2)`); named args need a small dedicated grammar rule (phase 3).
- Partial index with `USING` (`USING tin (body) WHERE active`): the base `create_index_stmt` only
  allows `WHERE` on the non-`USING` form. Out of scope unless `create_index_stmt` is overridden.
- Set-returning functions in `FROM` (`SELECT * FROM tin.tokenize(...)`) are not supported by
  SqlDelight. Use the select-list form `SELECT tin.tokenize('...')` instead. `tin.score_inspect`
  has no scalar form and is left out.

## 3. Project layout (mirror of pgsearch)

```
sqldelight-tin-search-module-app/
  settings.gradle.kts          rootProject "sqldelight-tin-search-module-app", include("tin-module")
  build.gradle.kts             app: sqldelight + flyway + application, module(project(":tin-module"))
  gradle.properties, gradlew, gradle/wrapper (copy from pgsearch)
  tin-module/
    build.gradle.kts           grammarKitComposer, maven-publish, jreleaser; artifactId "sqldelight-tin"
    src/main/kotlin/griffio/TinModule.kt
    src/main/kotlin/griffio/grammar/Tin.bnf            psiClassPrefix = "Tin"
    src/main/kotlin/griffio/grammar/mixins/TinTinqlOperatorMixin.kt
    src/main/resources/META-INF/services/app.cash.sqldelight.dialect.api.SqlDelightModule  -> griffio.TinModule
  src/main/kotlin/griffio/Main.kt
  src/main/sqldelight/griffio/migrations/V1__Initial_version.sqm   CREATE EXTENSION tin; posts table + index
  src/main/sqldelight/griffio/migrations/V2__fruits.sqm            two indexed columns
  src/main/sqldelight/griffio/migrations/V3__authors.sqm           join with a score per side
  src/main/sqldelight/griffio/queries/posts.sq, fruits.sq, authors.sq, tokenize.sq
  docker/Dockerfile            postgres:18 + Lead built from source (see §6)
  README.md
```

Versions: SqlDelight 2.4.0, Kotlin 2.3.10, intellij 231.9392.1, grammarKitComposer 0.1.12,
jreleaser 1.18.0, flyway 12.1.1, JVM toolchain 25 (all as in pgsearch).

## 4. Grammar (`Tin.bnf`)

```bnf
function_name ::= [ 'tin' DOT ] ID {
  extends = "com.alecstrong.sql.psi.core.psi.impl.SqlFunctionNameImpl"
  implements = "com.alecstrong.sql.psi.core.psi.SqlFunctionName"
}

index_method ::= 'tin' {
  extends = "app.cash.sqldelight.dialects.postgresql.grammar.psi.impl.PostgreSqlIndexMethodImpl"
  implements = "app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlIndexMethod"
}

storage_parameters ::= 'k1' | 'b' | 'score_stop_words'
  | 'tokenizer' | 'case_folding' | 'accent_folding' | 'long_tokens' | 'max_token_bytes' | 'graphemes' | 'position_gaps'
  | 'initial_segment_count' | 'target_segment_count' | 'max_mutable_segment_size' | 'max_merged_segment_size' | 'dead_percent_threshold' {
  extends = "app.cash.sqldelight.dialects.postgresql.grammar.psi.impl.PostgreSqlStorageParameterImpl"
  implements = "app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlStorageParameter"
}

private sql_column_expr ::= <<columnExprExt <<column_expr_real>>>>

tinql_operator ::= '==>'   (named tinql_* because the dialect already has match_operator_expression for @@)

tinql_operator_expression ::= sql_column_expr tinql_operator <<expr '-1'>> {
  mixin = "griffio.grammar.mixins.TinTinqlOperatorMixin"   // SqlBinaryExpr, like PgSearchProximityOperatorMixin
  pin = 2
}

extension_expr ::= tinql_operator_expression {
  extends = "app.cash.sqldelight.dialects.postgresql.grammar.psi.impl.PostgreSqlExtensionExprImpl"
  implements = "app.cash.sqldelight.dialects.postgresql.grammar.psi.PostgreSqlExtensionExpr"
}
```

Phase 3 addition (named arguments):

```bnf
named_argument ::= ID '=>' <<expr '-1'>>
score_function_expression ::= 'tin' DOT ( 'score' | 'full_score' ) LP <<expr '-1'>> ( COMMA ( named_argument | <<expr '-1'>> ) ) * RP
extension_expr ::= tinql_operator_expression | score_function_expression { ... }
```

## 5. Module and type resolver (`TinModule.kt`)

`setup()` copies the pgsearch chaining pattern for `function_name`, `extension_expr`,
`index_method`, `storage_parameters` (capture previous parser, try Tin parser first, fall back).
No `type_name` override: TIN adds no SQL types.

`TinTypeResolver : PostgreSqlTypeResolver(parentResolver)`:
- `resolvedType`: `TinExtensionExpr` with `tinqlOperatorExpression` → `BOOLEAN`
  (phase 3: `scoreFunctionExpression` → `REAL`).
- `functionType` by lowercase name:
  - `tin.score`, `tin.full_score`, `tin.max_score` → `REAL`
  - `tin.highlight`, `tin.highlight_ansi`, `tin.tokenize`, `tin.maybe_quote` → `TEXT`
  - else → `super.functionType(...)`

## 6. Local database: build Lead into a Postgres 18 image

There is no published Docker image or binary release for Lead. It is a Rust/pgrx extension:
Rust 1.96.0 (rust-toolchain.toml), `cargo-pgrx 0.19.1` exactly, Postgres 17 or 18.

`docker/Dockerfile` (multi-stage):
1. `FROM postgres:18` as builder: apt `build-essential clang pkg-config libssl-dev libclang-dev
   postgresql-server-dev-18 git curl`, install rustup 1.96.0, `cargo install cargo-pgrx --version 0.19.1 --locked`,
   `cargo pgrx init --pg18 $(which pg_config)`, clone planetscale/lead,
   `cargo pgrx install --release --package tin --no-default-features --features pg18`.
2. `FROM postgres:18`: copy `tin.so`, `tin.control`, `tin--*.sql` from the builder's pg lib/extension dirs.

Run: `docker build -t lead-pg18 docker && docker run --name lead -e POSTGRES_USER=myuser
-e POSTGRES_PASSWORD=mypassword -e POSTGRES_DB=mydatabase -p 5432:5432 -d lead-pg18`, then
`./gradlew build && ./gradlew flywayMigrate && ./gradlew run`.

Alternative for a quick start: build natively against a Homebrew Postgres 18 with `cargo pgrx install`.

Lead caveats that affect the sample:
- Scores are computed by rescanning rows, so keep sample tables small.
- `tin.score` elides "dense" terms (in ≥10% of docs). On a table of a few rows every term is
  dense and every score is `0.0`. Sample queries must use `tin.full_score(ctid)` or
  `tin.score(ctid, 1.5)` (dense_ratio > 1 disables elision) to show non-zero ranking.
- Database encoding must be UTF8 (the official image default is fine).

## 7. Phased feature list

### Phase 1 — core (highest value, lowest risk)
1. Gradle skeleton copied from pgsearch, module `tin-module`, artifact `io.github.griffio:sqldelight-tin`.
2. `USING tin (col)` index method + all storage parameters (`CREATE INDEX ... WITH`, `ALTER INDEX SET/RESET`).
3. `==>` operator → BOOLEAN, with `:query` bind parameter and literal TINQL strings.
4. `tin.score`, `tin.full_score`, `tin.max_score` → REAL (positional args), `ORDER BY score DESC LIMIT :n`.
5. `tin.highlight`, `tin.highlight_ansi` → TEXT (implicit and explicit query forms, custom tags).
6. `tin.tokenize(...)` in the select list → TEXT.
7. Sample migrations + queries covering: filter, count, ranked, relative score
   (`tin.score / tin.max_score`), several indexed columns with AND / OR and `^N` boost,
   btree + tin filter combo, join with a score per side (`p.ctid`, `a.ctid`), LATERAL top-k per author,
   `UPDATE ... WHERE body ==> ... RETURNING tin.highlight(body)`.
8. Dockerfile for Lead, README with the operator/function table and TINQL cheat sheet.

### Phase 2 — verify-only items (expected to work with the base grammar)
- `body ==> ANY (ARRAY['apple','grape'])` and `body ==> ANY (:terms)`.
- Expression index `USING tin ((lower(body)))` and matching `lower(body) ==> :q`
  (left side of the operator may need widening from `column_expr` to a function/paren expression,
  as the dialect does for `@@`: `( bind_expr | literal_expr | cast_expr | function_expr | column_expr )`).
- `CREATE INDEX CONCURRENTLY`, `REINDEX INDEX CONCURRENTLY`.

### Phase 3 — nice to have
- Named arguments `tin.score(ctid, k1 => 3.2, b => 0.2, dense_ratio => 0.25)` and
  `tin.highlight(body, query => 'a BEFORE b')` via a dedicated grammar rule (see §4).
- `tin.score` nullable variant note: under `FOR UPDATE` scores may be NULL; document rather than model.

### Out of scope
- Partial TIN index (`USING tin (body) WHERE active`) — needs a `create_index_stmt` override.
- `tin.score_inspect(...)` — set-returning function in `FROM`, unsupported by SqlDelight.
- Parsing or validating TINQL inside string literals.
- Partitioned-table specifics and operational GUCs (README pointers only).

## 8. Sample schema (from the docs, used by the .sqm files)

```sql
CREATE EXTENSION IF NOT EXISTS tin;

CREATE TABLE posts (
  id bigint PRIMARY KEY,
  category text NOT NULL,
  body text NOT NULL
);
INSERT INTO posts (id, category, body) VALUES
  (1, 'fruit', 'I love fuji apples and juicy mangoes'),
  (2, 'tasting', 'Grape tasting notes from the orchard'),
  (3, 'fruit', 'The best juicy fuji apple in town');
CREATE INDEX posts_body_tin ON posts USING tin (body) WITH (k1 = 1.2, b = 0.75);

CREATE TABLE fruits (id bigint PRIMARY KEY, name text NOT NULL, notes text NOT NULL);
CREATE INDEX fruits_name_tin  ON fruits USING tin (name);
CREATE INDEX fruits_notes_tin ON fruits USING tin (notes) WITH (tokenizer = whitespace, case_folding = preserve);

CREATE TABLE authors (id bigint PRIMARY KEY, name text NOT NULL, bio text NOT NULL, topics text NOT NULL);
CREATE INDEX authors_bio_tin ON authors USING tin (bio);
ALTER TABLE posts ADD COLUMN author_id bigint REFERENCES authors (id);
```

Representative `.sq` queries:

```sql
searchPosts:
SELECT id, body FROM posts WHERE body ==> :query;

rankPosts:
SELECT id, tin.full_score(ctid) AS score, body
FROM posts WHERE body ==> :query
ORDER BY score DESC LIMIT :limit;

relativeScore:
SELECT id, tin.score(ctid, 1.5) AS score, tin.score(ctid, 1.5) / tin.max_score(ctid) AS relative
FROM posts WHERE body ==> :query ORDER BY score DESC LIMIT 10;

highlightPosts:
SELECT id, tin.highlight(body) AS snippet FROM posts WHERE body ==> :query;

highlightMark:
SELECT tin.highlight(body, '<mark>', '</mark>', :query) FROM posts WHERE id = :id;

countMatches:
SELECT count(*) FROM posts WHERE body ==> :query;

searchFruits:
SELECT id, tin.full_score(ctid) AS score, name, notes
FROM fruits WHERE name ==> :nameQuery AND notes ==> :notesQuery
ORDER BY score DESC LIMIT 10;

joinScores:
SELECT p.id, p.body, tin.full_score(p.ctid) AS postScore, tin.full_score(a.ctid) AS authorScore
FROM posts p JOIN authors a ON a.id = p.author_id
WHERE p.body ==> :postQuery AND a.bio ==> :bioQuery
ORDER BY postScore + authorScore DESC LIMIT 10;

topPostsPerAuthor:
SELECT a.name, p.id, p.body, p.score
FROM authors a
CROSS JOIN LATERAL (
  SELECT id, body, tin.full_score(ctid) AS score FROM posts WHERE body ==> a.topics ORDER BY score DESC LIMIT 3
) p
ORDER BY a.name, p.score DESC;

tokenize:
SELECT tin.tokenize('Jalapeño 😀');

tokenizePreserveAccents:
SELECT tin.tokenize('Jalapeño 😀', 'unicode', 'fold', 'preserve');
```

## 9. Status (2026-09-23)

- Phase 1 implemented and `./gradlew build` passes: module, grammar, type resolver, three migrations, all sample queries, Dockerfile, README.
- Phase 2 verified at compile time: `==> ANY (ARRAY[...])`, LATERAL with an outer column on the right of `==>`, `UPDATE ... RETURNING tin.highlight(body)`, `ALTER INDEX SET/RESET`.
- Not yet run against a database: Docker was not running and Lead has no prebuilt image (see README).
- Gotcha found: `//` comments between rules in the `.bnf` are merged into the following rule by the grammar composer; the operator rule silently gained a bogus token and every `==>` failed to parse until the comments were removed.

## 10. Order of work

1. Copy pgsearch skeleton, rename to tin, strip pdb grammar; build with an empty `.sq` to confirm the module loads.
2. Add `index_method` + `storage_parameters`; write V1 migration; `./gradlew generateMainSampleMigrations`.
3. Add `==>` grammar + BOOLEAN resolver; `searchPosts`, `countMatches`.
4. Add function name/type mappings; ranked, highlight, tokenize queries.
5. Dockerfile for Lead; run `flywayMigrate` and `Main.kt` end to end.
6. Phase 2 verification queries; adjust operator left-hand side if expression indexes fail to parse.
7. README, publish `0.0.1` via jreleaser.
