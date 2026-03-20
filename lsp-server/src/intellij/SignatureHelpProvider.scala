package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.psi.{PsiElement, PsiMethod, PsiNamedElement}
import org.eclipse.lsp4j.{ParameterInformation, Position, SignatureHelp, SignatureInformation}
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction

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
    tryScFunction(element)
      .orElse(tryPsiMethod(element))
      .orElse(tryNavigationItem(element))

  /** Try to extract signature from Scala plugin's ScFunction using direct types. */
  private def tryScFunction(element: PsiElement): Option[SignatureInformation] =
    element match
      case fn: ScFunction =>
        try
          val name = fn.name
          val clauses = fn.effectiveParameterClauses
          val params = if clauses.nonEmpty then
            clauses.head.parameters.map: param =>
              val pName = param.name
              val pType = param.typeElement.map(_.getText).getOrElse("Any")
              (pName, pType)
            .toList
          else List.empty

          val paramStr = params.map(p => s"${p._1}: ${p._2}").mkString(", ")
          val label = s"$name($paramStr)"
          val sig = SignatureInformation(label)
          sig.setParameters(params.map((pName, pType) => ParameterInformation(s"$pName: $pType")).asJava)
          Some(sig)
        catch
          case _: Exception => None
      case _ => None

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
        val label = s"$name($paramStr)"
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
