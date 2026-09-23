package griffio.grammar.mixins

import com.alecstrong.sql.psi.core.psi.SqlBinaryExpr
import com.alecstrong.sql.psi.core.psi.SqlCompositeElementImpl
import com.alecstrong.sql.psi.core.psi.SqlExpr
import com.intellij.lang.ASTNode
import griffio.grammar.psi.TinTinqlOperatorExpression

/**
 * Used for `x ==> y` expressions in SqlBinaryExpr type resolver
 * https://planetscale.com/docs/postgres/search/reference/operator
 */
internal abstract class TinTinqlOperatorMixin(node: ASTNode) :
    SqlCompositeElementImpl(node),
    SqlBinaryExpr,
    TinTinqlOperatorExpression {

    override fun getExprList(): List<SqlExpr> {
        return children.filterIsInstance<SqlExpr>()
    }
}
