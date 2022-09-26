package zio.intellij.inspections.suggestions

import com.intellij.codeInspection._
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.codeInspection.{AbstractFixOnPsiElement, PsiElementVisitorSimple}
import org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTypeElement
import org.jetbrains.plugins.scala.lang.psi.types.result.Typeable
import org.jetbrains.plugins.scala.lang.psi.types.{ScType, TypePresentationContext}
import zio.intellij.inspections.suggestions.SuggestTypeAliasInspection.{createFix, AliasInfo}
import zio.intellij.intentions.suggestions.SuggestTypeAlias
import zio.intellij.utils.TypeCheckUtils.fromZioLike
import zio.intellij.utils.{ListSyntax, createTypeElement, extractTypeArguments, OptionUtils => OptionOps}

class SuggestTypeAliasInspection extends LocalInspectionTool {

  private def shouldSuggest(declaredType: ScType, aliases: List[AliasInfo]): Boolean =
    extractTypeArguments(declaredType)
      .map(_.length)
      .exists(args => aliases.exists(_.args < args))

  override def buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitorSimple = {
    case element @ (te: ScTypeElement) if isOnTheFly =>
      te match {
        case Typeable(tpe) if fromZioLike(tpe) =>
          val allAliases = SuggestTypeAlias.findMatchingAliases(te, tpe)
          val mostSpecificAliases =
            allAliases
              .flatMap(alias => extractTypeArguments(alias).map(_.length).map(AliasInfo(alias, _)))
              .minsBy(_.args)

          OptionOps.when(shouldSuggest(tpe, mostSpecificAliases)) {
            implicit val tpc: TypePresentationContext = TypePresentationContext(te)

            val typeElements = mostSpecificAliases.flatMap(alias => createTypeElement(alias.tpe, element))
            createFix(holder.getManager, isOnTheFly, te, typeElements, getDisplayName)
          }
        case _ => None
      }
    case _ => None
  }
}

object SuggestTypeAliasInspection {

  def hint(to: String): String = s"Replace with a more specific $to"

  def createFix(
    manager: InspectionManager,
    isOnTheFly: Boolean,
    from: ScTypeElement,
    to: Seq[ScTypeElement],
    descriptionTemplate: String
  ): ProblemDescriptor =
    manager.createProblemDescriptor(
      from,
      descriptionTemplate,
      isOnTheFly,
      to.map(new TypeAliasQuickFix(from, _)).toArray[LocalQuickFix],
      ProblemHighlightType.INFORMATION
    )

  final private class TypeAliasQuickFix(from: ScTypeElement, to: ScTypeElement)
      extends AbstractFixOnPsiElement(hint(to.getText), from) {
    override protected def doApplyFix(expr: ScTypeElement)(implicit project: Project): Unit =
      expr.replace(to)
  }

  final private case class AliasInfo(tpe: ScType, args: Int)
}
