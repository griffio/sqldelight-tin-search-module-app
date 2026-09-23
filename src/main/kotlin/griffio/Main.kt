package griffio

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import griffio.queries.Sample
import org.postgresql.ds.PGSimpleDataSource

private fun getSqlDriver() = PGSimpleDataSource().apply {
    setURL("jdbc:postgresql://localhost:5432/mydatabase")
    applicationName = "App Main"
    user = "myuser"
    password = "mypassword"
}.asJdbcDriver()

private fun section(name: String, block: () -> Unit) {
    println()
    println("== $name")
    block()
}

fun main() {
    val driver = getSqlDriver()
    val sample = Sample(driver)
    val posts = sample.postsQueries
    val fruits = sample.fruitsQueries
    val authors = sample.authorsQueries
    val tokenize = sample.tokenizeQueries

    section("searchPosts") { posts.searchPosts("juicy AND apple").executeAsList().forEach(::println) }
    section("searchPhrase") { posts.searchPhrase().executeAsList().forEach(::println) }
    section("searchAnyOf") { posts.searchAnyOf().executeAsList().forEach(::println) }
    section("countMatches") { println(posts.countMatches("juicy").executeAsOne()) }
    section("rankPosts") { posts.rankPosts("apple OR grape", 10).executeAsList().forEach(::println) }
    section("rankPostsBm25Override") { posts.rankPostsBm25Override("apple OR grape").executeAsList().forEach(::println) }
    section("relativeScore") { posts.relativeScore("fuji AND apple").executeAsList().forEach(::println) }
    section("highlightPosts") { posts.highlightPosts("apple").executeAsList().forEach(::println) }
    section("highlightMark") { posts.highlightMark("apple", 1).executeAsList().forEach(::println) }
    section("highlightWithLabel") { posts.highlightWithLabel().executeAsList().forEach(::println) }
    section("highlightAnsi") { posts.highlightAnsi("jalapeno").executeAsList().forEach(::println) }
    section("searchInCategory") { posts.searchInCategory("fuji", "fruit").executeAsList().forEach(::println) }
    section("reviewPosts") { posts.reviewPosts("coffee", "grinder").executeAsList().forEach(::println) }

    section("searchBothColumns") { fruits.searchBothColumns("fuji", "citrus").executeAsList().forEach(::println) }
    section("searchEitherColumn") { fruits.searchEitherColumn("citrus").executeAsList().forEach(::println) }
    section("searchBoostedName") { fruits.searchBoostedName().executeAsList().forEach(::println) }

    section("joinScores") { authors.joinScores("espresso", "barista OR roaster").executeAsList().forEach(::println) }
    section("topPostsPerAuthor") { authors.topPostsPerAuthor().executeAsList().forEach(::println) }

    section("tokenizeDefault") { println(tokenize.tokenizeDefault().executeAsList()) }
    section("tokenizePreserveAccents") { println(tokenize.tokenizePreserveAccents().executeAsList()) }
    section("tokenizeWhitespace") { println(tokenize.tokenizeWhitespace("Wi-Fi Café 😀").executeAsList()) }
}
