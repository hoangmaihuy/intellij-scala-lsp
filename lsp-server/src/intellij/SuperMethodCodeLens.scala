package org.jetbrains.scalalsP.intellij

import com.intellij.openapi.editor.Document
import com.intellij.psi.{PsiElement, PsiFile, PsiNameIdentifierOwner, PsiNamedElement}
import org.eclipse.lsp4j.{CodeLens, Command, Range}

// Code lens contributor that shows "overrides ClassName.methodName" lenses
// for methods that override a method from a supertype.
class SuperMethodCodeLens extends CodeLensContributor:

  override def id: String = "superMethod"

  override def collectLenses(psiFile: PsiFile, document: Document): Seq[CodeLens] =
    val lenses = Seq.newBuilder[CodeLens]
    visitAllElements(psiFile): element =>
      collectLens(element, document).foreach(lenses += _)
    lenses.result()

  override def resolve(codeLens: CodeLens): CodeLens =
    val data = extractData(codeLens)
    if data.isEmpty then return codeLens

    val title = data.getOrElse("title", "overrides method")
    val targetUri = data.getOrElse("targetUri", "")
    val targetLine = data.get("targetLine").map(_.toIntOption.getOrElse(0)).getOrElse(0)
    val targetChar = data.get("targetChar").map(_.toIntOption.getOrElse(0)).getOrElse(0)

    if targetUri.nonEmpty then
      val cmd = Command(title, "scala.gotoLocation",
        java.util.List.of[AnyRef](targetUri, targetLine.asInstanceOf[AnyRef], targetChar.asInstanceOf[AnyRef]))
      codeLens.setCommand(cmd)

    codeLens

  private def collectLens(element: PsiElement, document: Document): Option[CodeLens] =
    // Look for Scala functions (ScFunction) or Java PsiMethod that override something
    val superMethods = findSuperMethods(element)
    // For ScFunction with 'override' but no resolvable super, still create a lens
    val (superMethodOpt, title) = if superMethods.nonEmpty then
      val superMethod = superMethods.head
      val superClass = getContainingClassName(superMethod)
      val methodName = getMethodName(superMethod)
      (Some(superMethod), s"overrides $superClass.$methodName")
    else
      if ScalaTypes.isFunction(element) && ScalaTypes.hasModifierPropertyScala(element, "override") then
        // override is syntactically present but super method can't be resolved
        // (e.g., in light test environments where containingClass is unavailable)
        val fnName = element.asInstanceOf[PsiNamedElement].getName
        (None, s"overrides $fnName")
      else return None

    // Get the range at the method name (just the identifier)
    val range = element match
      case named: PsiNameIdentifierOwner =>
        Option(named.getNameIdentifier) match
          case Some(nameId) =>
            val start = PsiUtils.offsetToPosition(document, nameId.getTextRange.getStartOffset)
            val end = PsiUtils.offsetToPosition(document, nameId.getTextRange.getEndOffset)
            Range(start, end)
          case None =>
            PsiUtils.elementToRange(document, element)
      case _ => PsiUtils.elementToRange(document, element)

    // Find the location of the super method to navigate to on resolve
    val superLocation = superMethodOpt.flatMap(PsiUtils.elementToLocation)
    val (targetUri, targetLine, targetChar) = superLocation match
      case Some(loc) =>
        (loc.getUri, loc.getRange.getStart.getLine, loc.getRange.getStart.getCharacter)
      case None => ("", 0, 0)

    // Store data as a JSON object (will be serialized by lsp4j via Gson)
    val dataMap = new com.google.gson.JsonObject()
    dataMap.addProperty("contributorId", id)
    dataMap.addProperty("title", title)
    dataMap.addProperty("targetUri", targetUri)
    dataMap.addProperty("targetLine", targetLine)
    dataMap.addProperty("targetChar", targetChar)

    val lens = CodeLens(range)
    lens.setData(dataMap)
    Some(lens)

  private def findSuperMethods(element: PsiElement): Seq[PsiElement] =
    if ScalaTypes.isFunction(element) then
      try
        if !ScalaTypes.hasModifierPropertyScala(element, "override") then return Seq.empty
        // Try superMethods (requires containingClass to be non-null)
        val smList = try
          ScalaTypes.invokeMethod(element, "superMethods") match
            case Some(result) => result.asInstanceOf[scala.collection.Seq[?]].collect { case e: PsiElement => e }.toSeq
            case None => Seq.empty
        catch case _: Exception => Seq.empty
        if smList.nonEmpty then return smList
        // Try superSignaturesIncludingSelfType (what IntelliJ's line marker provider uses)
        val cc = try
          ScalaTypes.invokeMethod(element, "containingClass").orNull
        catch case _: Exception => null
        if cc != null then
          val supers = try
            ScalaTypes.invokeMethod(element, "superSignaturesIncludingSelfType") match
              case Some(result) => result.asInstanceOf[scala.collection.Seq[?]].toSeq
              case None => Seq.empty
          catch case _: Exception => Seq.empty
          if supers.nonEmpty then
            return supers.flatMap: sig =>
              try
                val namedEl = sig.getClass.getMethod("namedElement").invoke(sig)
                Option(namedEl).collect { case e: PsiElement if e != element => e }.toSeq
              catch case _: Exception => Seq.empty
        // Last fallback: superMethod
        try
          ScalaTypes.invokeOptionMethod(element, "superMethod") match
            case Some(m: PsiElement) => Seq(m)
            case _ => Seq.empty
        catch case _: Exception => Seq.empty
      catch
        case e: Exception =>
          System.err.println(s"[SuperMethodCodeLens] Error: ${e.getClass.getName}: ${e.getMessage}")
          Seq.empty
    else
      // Try reflection for Java PsiMethod
      findSuperMethodsReflective(element)

  private def findSuperMethodsReflective(element: PsiElement): Seq[PsiElement] =
    try
      val psiMethodClass = Class.forName("com.intellij.psi.PsiMethod")
      if !psiMethodClass.isInstance(element) then return Seq.empty
      val findSuperMethods = psiMethodClass.getMethod("findSuperMethods")
      val result = findSuperMethods.invoke(element).asInstanceOf[Array[AnyRef]]
      if result == null then Seq.empty
      else result.collect { case e: PsiElement => e }.toSeq
    catch
      case _: ClassNotFoundException => Seq.empty
      case _: NoSuchMethodException => Seq.empty
      case _: Exception => Seq.empty

  private def getContainingClassName(element: PsiElement): String =
    try
      if ScalaTypes.isFunction(element) then
        // Try containingClass, fall back to walking up the PSI tree
        val cc = try ScalaTypes.invokeMethod(element, "containingClass").orNull catch case _: Exception => null
        if cc != null then
          cc.asInstanceOf[PsiNamedElement].getName
        else
          findEnclosingClass(element).map(_.getName).getOrElse("?")
      else
        findEnclosingClass(element).map(_.getName).getOrElse("?")
    catch
      case _: Exception => "?"

  private def findEnclosingClass(element: PsiElement): Option[com.intellij.psi.PsiClass] =
    var parent = element.getParent
    while parent != null do
      parent match
        case cls: com.intellij.psi.PsiClass => return Some(cls)
        case _ => parent = parent.getParent
    None

  private def getMethodName(element: PsiElement): String =
    try
      element match
        case named: com.intellij.psi.PsiNamedElement => Option(named.getName).getOrElse("?")
        case _ => "?"
    catch
      case _: Exception => "?"

  private def extractData(codeLens: CodeLens): Map[String, String] =
    Option(codeLens.getData) match
      case Some(data: com.google.gson.JsonObject) =>
        Map(
          "contributorId" -> Option(data.get("contributorId")).map(_.getAsString).getOrElse(""),
          "title"         -> Option(data.get("title")).map(_.getAsString).getOrElse(""),
          "targetUri"     -> Option(data.get("targetUri")).map(_.getAsString).getOrElse(""),
          "targetLine"    -> Option(data.get("targetLine")).map(_.getAsString).getOrElse("0"),
          "targetChar"    -> Option(data.get("targetChar")).map(_.getAsString).getOrElse("0")
        )
      case Some(data: com.google.gson.JsonElement) =>
        try
          val obj = data.getAsJsonObject
          Map(
            "contributorId" -> Option(obj.get("contributorId")).map(_.getAsString).getOrElse(""),
            "title"         -> Option(obj.get("title")).map(_.getAsString).getOrElse(""),
            "targetUri"     -> Option(obj.get("targetUri")).map(_.getAsString).getOrElse(""),
            "targetLine"    -> Option(obj.get("targetLine")).map(_.getAsString).getOrElse("0"),
            "targetChar"    -> Option(obj.get("targetChar")).map(_.getAsString).getOrElse("0")
          )
        catch case _: Exception => Map.empty
      case _ => Map.empty

  private def visitAllElements(root: PsiElement)(visitor: PsiElement => Unit): Unit =
    visitor(root)
    var child = root.getFirstChild
    while child != null do
      visitAllElements(child)(visitor)
      child = child.getNextSibling
