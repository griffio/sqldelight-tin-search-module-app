package griffio

import app.cash.sqldelight.dialect.api.IntermediateType
import app.cash.sqldelight.dialect.api.PrimitiveType
import app.cash.sqldelight.dialect.api.SqlDelightModule
import app.cash.sqldelight.dialect.api.TypeResolver
import app.cash.sqldelight.dialects.postgresql.PostgreSqlTypeResolver
import app.cash.sqldelight.dialects.postgresql.grammar.PostgreSqlParser
import app.cash.sqldelight.dialects.postgresql.grammar.PostgreSqlParserUtil
import com.alecstrong.sql.psi.core.SqlParser
import com.alecstrong.sql.psi.core.SqlParserUtil
import com.alecstrong.sql.psi.core.psi.SqlExpr
import com.alecstrong.sql.psi.core.psi.SqlFunctionExpr
import com.intellij.lang.parser.GeneratedParserUtilBase.Parser
import griffio.grammar.TinParser
import griffio.grammar.TinParserUtil
import griffio.grammar.TinParserUtil.extension_expr
import griffio.grammar.TinParserUtil.function_name
import griffio.grammar.TinParserUtil.index_method
import griffio.grammar.TinParserUtil.storage_parameters
import griffio.grammar.psi.TinExtensionExpr

/**
 * SqlDelight module for the PlanetScale `tin` search extension
 * https://planetscale.com/docs/postgres/search
 */
class TinModule : SqlDelightModule {
    override fun typeResolver(parentResolver: TypeResolver): TypeResolver = TinTypeResolver(parentResolver)

    override fun setup() {
        TinParserUtil.reset()
        TinParserUtil.overridePostgreSqlParser()
        // The grammar doesn't support inheritance - override each rule manually and fall back to the
        // previously installed parser (e.g. another PostgreSql module) and then the PostgreSql dialect.
        val previousFunctionName = SqlParserUtil.function_name
        val previousExtensionExpr = PostgreSqlParserUtil.extension_expr
        val previousIndexMethod = PostgreSqlParserUtil.index_method
        val previousStorageParameters = PostgreSqlParserUtil.storage_parameters

        SqlParserUtil.function_name = Parser { psiBuilder, i ->
            function_name?.parse(psiBuilder, i)
                ?: TinParser.function_name_real(psiBuilder, i)
                || previousFunctionName?.parse(psiBuilder, i)
                ?: SqlParser.function_name_real(psiBuilder, i)
        }

        PostgreSqlParserUtil.extension_expr = Parser { psiBuilder, i ->
            extension_expr?.parse(psiBuilder, i)
                ?: TinParser.extension_expr_real(psiBuilder, i)
                || previousExtensionExpr?.parse(psiBuilder, i)
                ?: PostgreSqlParser.extension_expr_real(psiBuilder, i)
        }

        PostgreSqlParserUtil.index_method = Parser { psiBuilder, i ->
            index_method?.parse(psiBuilder, i)
                ?: TinParser.index_method_real(psiBuilder, i)
                || previousIndexMethod?.parse(psiBuilder, i)
                ?: PostgreSqlParser.index_method_real(psiBuilder, i)
        }

        PostgreSqlParserUtil.storage_parameters = Parser { psiBuilder, i ->
            storage_parameters?.parse(psiBuilder, i)
                ?: TinParser.storage_parameters_real(psiBuilder, i)
                || previousStorageParameters?.parse(psiBuilder, i)
                ?: PostgreSqlParser.storage_parameters_real(psiBuilder, i)
        }
    }
}

// Inheritance rather than delegation so that PostgreSqlTypeResolver behaviour is kept where not overridden.
// parentResolver is called to delegate to the next TypeResolver in the module chain.
private class TinTypeResolver(private val parentResolver: TypeResolver) : PostgreSqlTypeResolver(parentResolver) {
    override fun resolvedType(expr: SqlExpr): IntermediateType {
        return when (expr) {
            is TinExtensionExpr if (expr.tinqlOperatorExpression != null) -> IntermediateType(PrimitiveType.BOOLEAN)
            else -> parentResolver.resolvedType(expr)
        }
    }

    override fun functionType(functionExpr: SqlFunctionExpr): IntermediateType? =
        when (functionExpr.functionName.text.lowercase()) {
            // https://planetscale.com/docs/postgres/search/scoring
            "tin.score" -> IntermediateType(PrimitiveType.REAL)
            "tin.full_score" -> IntermediateType(PrimitiveType.REAL)
            "tin.max_score" -> IntermediateType(PrimitiveType.REAL)
            // https://planetscale.com/docs/postgres/search/highlighting
            "tin.highlight" -> IntermediateType(PrimitiveType.TEXT)
            "tin.highlight_ansi" -> IntermediateType(PrimitiveType.TEXT)
            // https://planetscale.com/docs/postgres/search/reference/indexes#tokenization
            "tin.tokenize" -> IntermediateType(PrimitiveType.TEXT)
            "tin.maybe_quote" -> IntermediateType(PrimitiveType.TEXT)
            else -> super.functionType(functionExpr) // PostgreSqlTypeResolver.functionType calls parentResolver
        }
}
