# SqlDelight 2.4.x PostgreSQL PlanetScale TIN search module support prototype

https://github.com/cashapp/sqldelight

**Experimental**

TIN is PlanetScale's full text search extension for Postgres: a `tin` index access method, the `==>` operator with the
TINQL query language, BM25 scoring and highlighting.

https://planetscale.com/docs/postgres/search

Use with SqlDelight `2.4.0`

---

Instead of a new dialect or adding PostgreSql extensions into the core PostgreSql grammar,
use a custom SqlDelight module to implement grammar and type resolvers for TIN operations.

Module artifact: `io.github.griffio:sqldelight-tin` (not yet published)

```kotlin
sqldelight {
    databases {
        create("Sample") {
            dialect(libs.sqldelight.postgresql.dialect)
            module(project(":tin-module")) // or module("io.github.griffio:sqldelight-tin:0.0.1")
        }
    }
}
```

## What the module adds to the PostgreSql dialect

| SQL | Kotlin type |
| --- | --- |
| `CREATE INDEX ... USING tin (col)` | |
| `WITH (k1 = 1.2, b = 0.75, score_stop_words = '...', tokenizer = whitespace, case_folding = fold, accent_folding = preserve, long_tokens = split, max_token_bytes = 64, graphemes = emoji, position_gaps = collapse, initial_segment_count = 8, target_segment_count = 8, max_mutable_segment_size = 4194304, max_merged_segment_size = 2000, dead_percent_threshold = 0.5)` and `ALTER INDEX ... SET/RESET (...)` | |
| `col ==> 'TINQL'`, `col ==> :query`, `col ==> ANY (ARRAY[...])`, `col ==> outer.column` | `Boolean` |
| `tin.score(ctid [, dense_ratio, k1, b, term_add, term_replace])` | `Double` |
| `tin.full_score(ctid [, k1, b])` | `Double` |
| `tin.max_score(ctid)` | `Double` |
| `tin.highlight(text [, begin_tag, end_tag, query])` | `String` |
| `tin.highlight_ansi(text [, wrap_to, query])` | `String` |
| `tin.tokenize(text [, tokenizer, case_folding, accent_folding, long_tokens, max_token_bytes, graphemes, position_gaps])` in the select list | `String` per row |
| `tin.maybe_quote(text)` | `String` |

`ctid` is already a synthesized column in the SqlDelight PostgreSql dialect, so `tin.score(ctid)` and `tin.score(p.ctid)` resolve.

Not supported (yet):

- Named arguments `tin.score(ctid, k1 => 3.2)`. Use positional arguments: `tin.score(ctid, 0.1, 3.2, 0.2)`.
- Partial `USING tin (col) WHERE ...` indexes (the dialect grammar only allows `WHERE` on the non-`USING` form).
- `tin.score_inspect(...)` and `SELECT * FROM tin.tokenize(...)` (set-returning functions in `FROM`).

## Sample

```sql
CREATE EXTENSION IF NOT EXISTS tin;

CREATE TABLE posts (
  id BIGINT PRIMARY KEY,
  category TEXT NOT NULL,
  body TEXT NOT NULL
);

CREATE INDEX posts_body_tin ON posts USING tin (body) WITH (k1 = 1.2, b = 0.75);
```

```sql
searchPosts:
SELECT id, body
FROM posts
WHERE body ==> :query;

rankPosts:
SELECT id, tin.full_score(ctid) AS score, body
FROM posts
WHERE body ==> :query
ORDER BY score DESC
LIMIT :limit;

relativeScore:
SELECT id,
       tin.score(ctid, 1.5) AS score,
       tin.score(ctid, 1.5) / tin.max_score(ctid) AS relative,
       body
FROM posts
WHERE body ==> :query
ORDER BY score DESC
LIMIT 10;

highlightMark:
SELECT tin.highlight(body, '<mark>', '</mark>', :query) AS highlighted
FROM posts
WHERE id = :id;

joinScores:
SELECT p.id, p.body,
       tin.full_score(p.ctid) AS postScore,
       tin.full_score(a.ctid) AS authorScore
FROM posts p
JOIN authors a ON a.id = p.author_id
WHERE p.body ==> :postQuery
  AND a.bio ==> :bioQuery
ORDER BY postScore + authorScore DESC
LIMIT 10;
```

See `src/main/sqldelight/griffio/queries/*.sq` for filter, count, `ANY`, multi-column, boost, LATERAL top-k per author,
`UPDATE ... RETURNING tin.highlight(body)` and tokenizer examples.

### TINQL cheat sheet

Keywords are UPPERCASE, lowercase tokens are terms.

| Syntax | Meaning |
| --- | --- |
| `apple grape`, `apple AND grape`, `apple OR grape`, `apple AND NOT peel` | boolean |
| `"fuji apple"`, `"big _ wolf"`, `"big [bad large] wolf"`, `"fuji apple"~2` | phrase, gap, alternatives, tolerance |
| `appl*`, `p?ach`, `apple~2`, `MATCHES peach.*`, `aardvark TO cat` | wildcard, fuzzy, regex, range |
| `[mango plum pear]`, `AT LEAST 2 OF [a b c]`, `ALL OF [a b c]` | alternatives |
| `fuji THEN/0 apple`, `peach NEAR/5 blossom`, `(a NEAR/5 b) WITHIN 6` | proximity |
| `A ENCLOSES B`, `A ENCLOSED BY B`, `A OVERLAPPING B`, `A BEFORE B`, `A AFTER B` | span relations |
| `apple IN FIRST 100 WORDS`, `apple IN LAST 25%`, `apple IN WORDS 500 TO 1000` | positional |
| `apple^2` | boost |

## Local database

PlanetScale publishes no Docker image for TIN. For local development they provide
[Lead](https://github.com/planetscale/lead), a Postgres 17/18 extension with the same SQL surface that scans rows
instead of maintaining a real index. It is built from source with Rust and `cargo-pgrx`, so `docker/Dockerfile`
builds it into the official `postgres:18` image.

```shell
docker build -t lead-pg18 docker

docker run \
  --name lead \
  -e POSTGRES_USER=myuser \
  -e POSTGRES_PASSWORD=mypassword \
  -e POSTGRES_DB=mydatabase \
  -p 5432:5432 \
  -d lead-pg18
```

```shell
./gradlew build &&
./gradlew flywayMigrate &&
./gradlew run
```

Lead caveats that affect the sample:

- `tin.score` leaves out "dense" terms (found in 10% or more of the documents). On a table of a few rows every term is
  dense and every score is `0.0`. The sample uses `tin.full_score(ctid)` or `tin.score(ctid, 1.5)` (a `dense_ratio`
  above 1 disables elision) so ranking is visible.
- Scores are computed by rescanning the matched rows, so keep sample tables small.

## Module notes

- `tin-module/src/main/kotlin/griffio/grammar/Tin.bnf` overrides `function_name`, `index_method`, `storage_parameters`
  and `extension_expr` of the PostgreSql dialect grammar, chained so other PostgreSql modules still work.
- `TinModule.kt` wires the parser overrides and a `TypeResolver` that maps `==>` to `BOOLEAN` and the `tin.*`
  functions to `REAL` / `TEXT`.
- Do not put `//` comments between rules in the `.bnf`: the grammar-kit composer merges them into the next rule and
  silently breaks it.
