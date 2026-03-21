package org.jetbrains.scalalsP.intellij

import com.intellij.lang.LanguageDocumentation
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiMethod, PsiNamedElement}
import org.eclipse.lsp4j.{MarkupContent, MarkupKind, ParameterInformation, Position, SignatureHelp, SignatureInformation}

import scala.jdk.CollectionConverters.*

class SignatureHelpProvider(projectManager: IntellijProjectManager):

  def getSignatureHelp(uri: String, position: Position): Option[SignatureHelp] =
    try
      projectManager.smartReadAction: () =>
        for
          psiFile <- projectManager.findPsiFile(uri)
          vf <- projectManager.findVirtualFile(uri)
          document <- Option(FileDocumentManager.getInstance().getDocument(vf))
          result <- computeSignatureHelp(psiFile, document, position)
        yield result
    catch
      case e: Exception =>
        System.err.println(s"[SignatureHelp] Error: ${e.getMessage}")
        None

  private def computeSignatureHelp(
    psiFile: com.intellij.psi.PsiFile,
    document: com.intellij.openapi.editor.Document,
    position: Position
  ): Option[SignatureHelp] =
    val offset = PsiUtils.positionToOffset(document, position)
    val text = document.getText

    // Walk backwards from cursor to find the opening '(' of the argument list
    val parenInfo = findArgListStart(text, offset)
    parenInfo.flatMap: (parenOffset, commaCount) =>
      // Find the method reference just before the '('
      findMethodElement(psiFile, text, parenOffset).flatMap: methodElement =>
        val signatures = extractSignatures(methodElement)
        if signatures.isEmpty then None
        else
          val help = SignatureHelp()
          help.setSignatures(signatures.asJava)
          help.setActiveSignature(0)
          help.setActiveParameter(commaCount)
          Some(help)

  /** Walk backwards from offset to find the opening '(' that starts the argument list.
    * Returns (offset of '(', number of commas between '(' and cursor). */
  private def findArgListStart(text: String, offset: Int): Option[(Int, Int)] =
    var depth = 0
    var commas = 0
    var i = math.min(offset - 1, text.length - 1)
    while i >= 0 do
      val ch = text.charAt(i)
      ch match
        case ')' | ']' | '}' => depth += 1
        case '(' =>
          if depth == 0 then return Some((i, commas))
          else depth -= 1
        case '[' | '{' =>
          if depth > 0 then depth -= 1
        case ',' =>
          if depth == 0 then commas += 1
        case _ => ()
      i -= 1
    None

  /** Find the method/function PSI element just before the opening parenthesis. */
  private def findMethodElement(
    psiFile: com.intellij.psi.PsiFile,
    text: String,
    parenOffset: Int
  ): Option[PsiElement] =
    // Scan backwards from just before '(' to find the identifier end
    var i = parenOffset - 1
    while i >= 0 && text.charAt(i).isWhitespace do i -= 1
    if i < 0 then return None

    // Now find the reference at this position
    PsiUtils.findReferenceElementAt(psiFile, i).flatMap: elem =>
      val ref = elem.getReference
      if ref != null then
        Option(ref.resolve())
      else
        Some(elem)

  /** Extract signature information from a resolved PSI element.
    * Tries Scala plugin's ScFunction first via reflection, falls back to PsiMethod, then NavigationItem. */
  private def extractSignatures(element: PsiElement): List[SignatureInformation] =
    // Try to get overloaded methods — look for siblings with the same name
    val allMethods = findOverloads(element)
    val methods = if allMethods.nonEmpty then allMethods else List(element)
    methods.flatMap(extractSingleSignature)

  private def findOverloads(element: PsiElement): List[PsiElement] =
    try
      element match
        case named: PsiNamedElement =>
          val name = named.getName
          if name == null then return List(element)
          val parent = element.getParent
          if parent == null then return List(element)
          val siblings = parent.getChildren.toList
          siblings.filter: child =>
            child.isInstanceOf[PsiNamedElement] &&
              child.asInstanceOf[PsiNamedElement].getName == name
        case _ => List(element)
    catch
      case _: Exception => List(element)

  private def extractSingleSignature(element: PsiElement): Option[SignatureInformation] =
    // Try ScFunction reflection first
    val sigOpt = tryScFunction(element)
      .orElse(tryPsiMethod(element))
      .orElse(tryNavigationItem(element))

    sigOpt.map: sig =>
      // Attach ScalaDoc documentation if available
      try
        val docProvider = LanguageDocumentation.INSTANCE.forLanguage(element.getLanguage)
        if docProvider != null then
          val docText = docProvider.generateDoc(element, null)
          if docText != null && docText.nonEmpty then
            sig.setDocumentation(MarkupContent(MarkupKind.MARKDOWN, HoverProvider.htmlToMarkdown(docText)))
      catch
        case _: Exception => () // ignore documentation errors
      sig

  /** Try to extract signature from Scala plugin's ScFunction using reflection. */
  private def tryScFunction(element: PsiElement): Option[SignatureInformation] =
    if !ScalaTypes.isFunction(element) then return None
    try
      val name = element.asInstanceOf[PsiNamedElement].getName
      val clausesRaw = ScalaTypes.invokeMethod(element, "effectiveParameterClauses")
        .getOrElse(return None)
      val clauses = clausesRaw.asInstanceOf[scala.collection.Seq[?]]

      // Collect all parameters across all clauses for ParameterInformation
      val allParams = scala.collection.mutable.ListBuffer.empty[(String, String)]

      // Build label from all parameter clauses
      val clauseStrings = clauses.map: clause =>
        val clauseParamsRaw = clause.getClass.getMethod("parameters").invoke(clause)
        val clauseParams = clauseParamsRaw.asInstanceOf[scala.collection.Seq[?]].map: param =>
          val pName = param.asInstanceOf[PsiNamedElement].getName
          val pTypeOpt = try
            param.getClass.getMethod("typeElement").invoke(param) match
              case opt: Option[?] => opt.map(te => te.asInstanceOf[PsiElement].getText)
              case _ => None
          catch case _: Exception => None
          val pType = pTypeOpt.getOrElse("Any")
          allParams += ((pName, pType))
          s"$pName: $pType"
        .toList
        val paramsStr = clauseParams.mkString(", ")

        // Check if this clause is implicit or using
        val hasImplicit = try clause.getClass.getMethod("hasImplicitKeyword").invoke(clause).asInstanceOf[Boolean]
          catch case _: Exception => false
        val hasUsing = try clause.getClass.getMethod("hasUsingKeyword").invoke(clause).asInstanceOf[Boolean]
          catch case _: Exception => false

        if hasImplicit then s"(implicit $paramsStr)"
        else if hasUsing then s"(using $paramsStr)"
        else s"($paramsStr)"
      .toList

      val returnTypeStr = try
        ScalaTypes.invokeMethod(element, "returnTypeElement") match
          case Some(rte) => rte.asInstanceOf[PsiElement].getText
          case None => "Unit"
      catch case _: Exception => "Unit"
      val label = s"$name${clauseStrings.mkString}: $returnTypeStr"

      val sig = SignatureInformation(label)
      sig.setParameters(allParams.map((pName, pType) => ParameterInformation(s"$pName: $pType")).asJava)
      Some(sig)
    catch
      case _: Exception => None

  /** Try to extract signature from a standard Java PsiMethod. */
  private def tryPsiMethod(element: PsiElement): Option[SignatureInformation] =
    element match
      case method: PsiMethod =>
        val name = method.getName
        val params = method.getParameterList.getParameters.toList
        val paramInfos = params.map: p =>
          val pName = Option(p.getName).getOrElse("?")
          val pType = Option(p.getType).map(_.getPresentableText).getOrElse("Any")
          (pName, pType)
        val paramStr = paramInfos.map(p => s"${p._1}: ${p._2}").mkString(", ")
        val returnTypeStr = Option(method.getReturnType).map(_.getPresentableText).getOrElse("Unit")
        val label = s"$name($paramStr): $returnTypeStr"
        val sig = SignatureInformation(label)
        sig.setParameters(paramInfos.map: (pName, pType) =>
          ParameterInformation(s"$pName: $pType")
        .asJava)
        Some(sig)
      case _ => None

  /** Last resort: use NavigationItem presentation for a basic signature. */
  private def tryNavigationItem(element: PsiElement): Option[SignatureInformation] =
    element match
      case nav: com.intellij.navigation.NavigationItem =>
        Option(nav.getPresentation).map: pres =>
          val text = Option(pres.getPresentableText).getOrElse("unknown")
          val sig = SignatureInformation(text)
          sig.setParameters(java.util.List.of())
          sig
      case _ => None
