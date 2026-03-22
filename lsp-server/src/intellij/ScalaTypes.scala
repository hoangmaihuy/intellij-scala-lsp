package org.jetbrains.scalalsP.intellij

import com.intellij.psi.{PsiElement, PsiNamedElement}
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-classloader type checks for Scala plugin types.
 *
 * IntelliJ's PluginClassLoader (child-first) loads Scala plugin types separately
 * from our code on the boot classpath. Direct instanceof fails because the same
 * class loaded by different classloaders are different types.
 *
 * This object uses Class.forName with the element's own classloader + isInstance,
 * which works regardless of which classloader loaded the class.
 */
object ScalaTypes:

  // Cache loaded classes per (classloader, fqn) to avoid repeated Class.forName
  private val classCache = new ConcurrentHashMap[(ClassLoader, String), Option[Class[?]]]()

  private def loadClass(cl: ClassLoader, fqn: String): Option[Class[?]] =
    val key = (cl, fqn)
    classCache.computeIfAbsent(key, _ =>
      try Some(cl.loadClass(fqn))
      catch case _: Exception => None
    )

  private def isInstanceOfScala(e: PsiElement, fqn: String): Boolean =
    if e == null then return false
    loadClass(e.getClass.getClassLoader, fqn) match
      case Some(cls) => cls.isInstance(e)
      case None => false

  // Also check non-PsiElement (e.g. parent which is Any)
  private def isInstanceOfAny(e: Any, fqn: String): Boolean =
    e match
      case null => false
      case psi: PsiElement => isInstanceOfScala(psi, fqn)
      case other =>
        try
          val cls = other.getClass.getClassLoader.loadClass(fqn)
          cls.isInstance(other)
        catch case _: Exception => false

  // --- Type hierarchy checks ---

  def isTypeDefinition(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition")

  def isClass(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScClass")

  def isTrait(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTrait")

  def isObject(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject")

  def isEnum(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScEnum")

  def isTemplateDefinition(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition")

  // --- Statements ---

  def isFunction(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunction")

  def isFunctionDefinition(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDefinition")

  def isFunctionDeclaration(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScFunctionDeclaration")

  def isValue(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScValue")

  def isPatternDefinition(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScPatternDefinition")

  def isVariable(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariable")

  def isVariableDefinition(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScVariableDefinition")

  def isTypeAlias(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScTypeAlias")

  def isGiven(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.ScGiven")

  def isPackaging(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging")

  def isPackage(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.impl.ScPackageImpl") ||
    isInstanceOfAny(e, "com.intellij.psi.PsiPackage")

  def isSyntheticClass(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.ScSyntheticClass")

  def isSyntheticFunction(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.ScSyntheticFunction")

  def isField(e: PsiElement): Boolean =
    isInstanceOfAny(e, "com.intellij.psi.PsiField")

  def isMethod(e: PsiElement): Boolean =
    isInstanceOfAny(e, "com.intellij.psi.PsiMethod")

  def isClassLike(e: PsiElement): Boolean =
    isInstanceOfAny(e, "com.intellij.psi.PsiClass")

  // --- Params and patterns ---

  def isParameter(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScParameter")

  def isTypeParam(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.statements.params.ScTypeParam")

  def isBindingPattern(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.patterns.ScBindingPattern")

  def isFieldId(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.ScFieldId")

  // --- References ---

  def isReference(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.ScReference")

  def isStableCodeReference(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReference")

  def isReferenceExpression(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.expr.ScReferenceExpression")

  def isMethodCall(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.expr.ScMethodCall")

  // --- Expressions and type elements ---

  def isArgumentExprList(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.expr.ScArgumentExprList")

  def isExpression(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.expr.ScExpression")

  def isImplicitArgumentsOwner(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.ImplicitArgumentsOwner")

  def isTemplateBody(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.toplevel.templates.ScTemplateBody")

  def isPrimaryConstructor(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.ScPrimaryConstructor")

  def isAnnotationsHolder(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.ScAnnotationsHolder")

  // Type elements
  def isSimpleTypeElement(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScSimpleTypeElement")

  def isParameterizedTypeElement(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScParameterizedTypeElement")

  def isFunctionalTypeElement(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScFunctionalTypeElement")

  def isTupleTypeElement(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScTupleTypeElement")

  def isInfixTypeElement(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScInfixTypeElement")

  def isCompoundTypeElement(e: PsiElement): Boolean =
    isInstanceOfScala(e, "org.jetbrains.plugins.scala.lang.psi.api.base.types.ScCompoundTypeElement")

  // --- Reflection-based operations ---
  // These call Scala plugin methods using reflection with the element's own classloader.

  /** Call ScalaPsiUtil.getCompanionModule(typeDefinition) via reflection. */
  def getCompanionModule(e: PsiElement): Option[PsiNamedElement] =
    if !isTypeDefinition(e) then return None
    try
      val cl = e.getClass.getClassLoader
      val utilClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.ScalaPsiUtil")
      val tdClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition")
      val method = utilClass.getMethod("getCompanionModule", tdClass)
      method.invoke(null, e).asInstanceOf[Option[?]].flatMap:
        case n: PsiNamedElement => Some(n)
        case _ => None
    catch case _: Exception => None

  /** Check if an ScAnnotationsHolder has a specific annotation. */
  def hasAnnotation(e: PsiElement, fqn: String): Boolean =
    if !isAnnotationsHolder(e) then return false
    try
      val method = e.getClass.getMethod("hasAnnotation", classOf[String])
      method.invoke(e, fqn).asInstanceOf[Boolean]
    catch case _: Exception => false

  /** Get annotations from an ScAnnotationsHolder. Returns empty if not applicable. */
  def getAnnotations(e: PsiElement): Seq[PsiElement] =
    if !isAnnotationsHolder(e) then return Seq.empty
    try
      val method = e.getClass.getMethod("annotations")
      val result = method.invoke(e)
      // Scala Seq — iterate via Java interop
      import scala.jdk.CollectionConverters.*
      result match
        case seq: scala.collection.Seq[?] => seq.collect { case p: PsiElement => p }.toSeq
        case _ => Seq.empty
    catch case _: Exception => Seq.empty

  /** Check if ScClass.isCase. */
  def isCase(e: PsiElement): Boolean =
    if !isClass(e) then return false
    try
      e.getClass.getMethod("isCase").invoke(e).asInstanceOf[Boolean]
    catch case _: Exception => false

  /** Check if ScBindingPattern.isClassMember. */
  def isClassMember(e: PsiElement): Boolean =
    if !isBindingPattern(e) then return false
    try
      e.getClass.getMethod("isClassMember").invoke(e).asInstanceOf[Boolean]
    catch case _: Exception => false

  /** Check if ScBindingPattern.isVal. */
  def isVal(e: PsiElement): Boolean =
    if !isBindingPattern(e) then return false
    try
      e.getClass.getMethod("isVal").invoke(e).asInstanceOf[Boolean]
    catch case _: Exception => false

  /** Check if ScBindingPattern.isVar. */
  def isVar(e: PsiElement): Boolean =
    if !isBindingPattern(e) then return false
    try
      e.getClass.getMethod("isVar").invoke(e).asInstanceOf[Boolean]
    catch case _: Exception => false

  /** Get ScReference.nameId as PsiElement. */
  def getNameId(e: PsiElement): Option[PsiElement] =
    if !isReference(e) then return None
    try
      val result = e.getClass.getMethod("nameId").invoke(e)
      result match
        case p: PsiElement => Some(p)
        case _ => None
    catch case _: Exception => None

  /** Check ScFunction.hasModifierPropertyScala(name). */
  def hasModifierPropertyScala(e: PsiElement, name: String): Boolean =
    if !isFunction(e) then return false
    try
      e.getClass.getMethod("hasModifierPropertyScala", classOf[String]).invoke(e, name).asInstanceOf[Boolean]
    catch case _: Exception => false

  /** Invoke a no-arg method by name, returning the result or None. */
  def invokeMethod(e: PsiElement, methodName: String): Option[Any] =
    try
      val result = e.getClass.getMethod(methodName).invoke(e)
      Option(result)
    catch case _: Exception => None

  /** Invoke a no-arg method that returns a Scala Option. */
  def invokeOptionMethod(e: PsiElement, methodName: String): Option[Any] =
    try
      e.getClass.getMethod(methodName).invoke(e) match
        case opt: Option[?] => opt
        case _ => None
    catch case _: Exception => None

  /**
   * Get the presentable type text for a Scala typed definition (ScPatternDefinition,
   * ScVariableDefinition, ScFunctionDefinition) via reflection.
   *
   * Uses the element's classloader to access TypePresentationContext, Context, and
   * presentableText on the ScType. Returns None if anything goes wrong — the hint
   * simply won't be shown.
   */
  def getTypeText(e: PsiElement): Option[String] =
    try
      val cl = e.getClass.getClassLoader

      // Load TypePresentationContext and Context classes
      val tpcCompanion = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext$")
      val tpcModule = tpcCompanion.getField("MODULE$").get(null)
      val tpcClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext")
      val applyTpc = tpcCompanion.getMethod("apply", classOf[PsiElement])
      val tpc = applyTpc.invoke(tpcModule, e)

      val ctxCompanion = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.Context$")
      val ctxModule = ctxCompanion.getField("MODULE$").get(null)
      val ctxClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.Context")
      val applyCtx = ctxCompanion.getMethod("apply", classOf[PsiElement])
      val ctx = applyCtx.invoke(ctxModule, e)

      def presentable(scType: Any): Option[String] =
        try
          val method = scType.getClass.getMethod("presentableText", tpcClass, ctxClass)
          Some(method.invoke(scType, tpc, ctx).asInstanceOf[String])
        catch case ex: Exception =>
          System.err.println(s"ScalaTypes.getTypeText: presentableText failed: $ex")
          None

      // Try ScPatternDefinition.type() or ScVariableDefinition.type()
      val typeResult =
        try e.getClass.getMethod("type").invoke(e)
        catch case _: Exception => null
      if typeResult != null then
        // TypeResult has .toOption
        val optResult = typeResult.getClass.getMethod("toOption").invoke(typeResult)
        optResult match
          case opt: Option[?] => return opt.flatMap(t => presentable(t))
          case _ => ()

      // Try ScFunctionDefinition.returnType
      val retType =
        try e.getClass.getMethod("returnType").invoke(e)
        catch case _: Exception => null
      if retType != null then
        // TypeResult has .toOption
        val optResult = retType.getClass.getMethod("toOption").invoke(retType)
        optResult match
          case opt: Option[?] => return opt.flatMap(t => presentable(t))
          case _ => ()

      None
    catch case ex: Exception =>
      System.err.println(s"ScalaTypes.getTypeText: failed for ${e.getClass.getName}: $ex")
      None

  /**
   * Get presentable type text for a ScType object, using the given element for context.
   * Used for type parameter hints where we already have the substituted type.
   */
  def getScTypePresentableText(scType: Any, contextElement: PsiElement): Option[String] =
    try
      val cl = contextElement.getClass.getClassLoader

      val tpcCompanion = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext$")
      val tpcModule = tpcCompanion.getField("MODULE$").get(null)
      val tpcClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.TypePresentationContext")
      val applyTpc = tpcCompanion.getMethod("apply", classOf[PsiElement])
      val tpc = applyTpc.invoke(tpcModule, contextElement)

      val ctxCompanion = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.Context$")
      val ctxModule = ctxCompanion.getField("MODULE$").get(null)
      val ctxClass = cl.loadClass("org.jetbrains.plugins.scala.lang.psi.types.Context")
      val applyCtx = ctxCompanion.getMethod("apply", classOf[PsiElement])
      val ctx = applyCtx.invoke(ctxModule, contextElement)

      val method = scType.getClass.getMethod("presentableText", tpcClass, ctxClass)
      Some(method.invoke(scType, tpc, ctx).asInstanceOf[String])
    catch case ex: Exception =>
      System.err.println(s"ScalaTypes.getScTypePresentableText: failed: $ex")
      None
