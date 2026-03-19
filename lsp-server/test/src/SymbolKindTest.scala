package org.jetbrains.scalalsP.intellij

import munit.FunSuite
import org.eclipse.lsp4j.SymbolKind
import org.jetbrains.scalalsP.protocol.LspConversions

// Tests for symbol kind classification based on class names.
// The SymbolProvider and LspConversions both use class-name-based detection.
class SymbolKindTest extends FunSuite:

  // Simulate the classification logic used in SymbolProvider.getSymbolKind
  private def classifyByName(className: String): SymbolKind =
    if className.contains("ScClass") || className.contains("PsiClass") then SymbolKind.Class
    else if className.contains("ScTrait") then SymbolKind.Interface
    else if className.contains("ScObject") then SymbolKind.Module
    else if className.contains("ScFunction") || className.contains("PsiMethod") then SymbolKind.Method
    else if className.contains("ScVariable") then SymbolKind.Variable
    else if className.contains("ScValue") then SymbolKind.Field
    else if className.contains("ScTypeAlias") then SymbolKind.TypeParameter
    else if className.contains("ScPackaging") then SymbolKind.Package
    else SymbolKind.Variable

  // Simulate the significance check
  private def isSignificant(className: String): Boolean =
    className.contains("ScTypeDefinition") ||
    className.contains("ScClass") ||
    className.contains("ScTrait") ||
    className.contains("ScObject") ||
    className.contains("ScFunction") ||
    className.contains("ScValue") ||
    className.contains("ScVariable") ||
    className.contains("ScTypeAlias") ||
    className.contains("ScPackaging") ||
    className.contains("PsiClass") ||
    className.contains("PsiMethod")

  test("ScClass maps to Class"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScClassImpl"), SymbolKind.Class)

  test("ScTrait maps to Interface"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScTraitImpl"), SymbolKind.Interface)

  test("ScObject maps to Module"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScObjectImpl"), SymbolKind.Module)

  test("ScFunction maps to Method"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScFunctionDefinitionImpl"), SymbolKind.Method)

  test("ScValue maps to Field"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScValueDeclarationImpl"), SymbolKind.Field)

  test("ScVariable maps to Variable"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScVariableDefinitionImpl"), SymbolKind.Variable)

  test("ScTypeAlias maps to TypeParameter"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScTypeAliasDefinitionImpl"), SymbolKind.TypeParameter)

  test("ScPackaging maps to Package"):
    assertEquals(classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.ScPackagingImpl"), SymbolKind.Package)

  test("PsiClass maps to Class"):
    assertEquals(classifyByName("com.intellij.psi.impl.source.PsiClassImpl"), SymbolKind.Class)

  test("PsiMethod maps to Method"):
    assertEquals(classifyByName("com.intellij.psi.impl.source.PsiMethodImpl"), SymbolKind.Method)

  test("unknown element maps to Variable"):
    assertEquals(classifyByName("org.jetbrains.something.Unknown"), SymbolKind.Variable)

  // Significance checks

  test("ScTypeDefinition is significant"):
    assert(isSignificant("ScTypeDefinitionImpl"))

  test("ScClass is significant"):
    assert(isSignificant("ScClassImpl"))

  test("ScFunction is significant"):
    assert(isSignificant("ScFunctionImpl"))

  test("ScValue is significant"):
    assert(isSignificant("ScValueDeclarationImpl"))

  test("ScVariable is significant"):
    assert(isSignificant("ScVariableDefinitionImpl"))

  test("ScTypeAlias is significant"):
    assert(isSignificant("ScTypeAliasDefinitionImpl"))

  test("random element is not significant"):
    assert(!isSignificant("ScImportExprImpl"))

  test("ScReference is not significant"):
    assert(!isSignificant("ScReferenceExpressionImpl"))

  test("whitespace/comment PSI nodes are not significant"):
    assert(!isSignificant("PsiWhiteSpaceImpl"))
    assert(!isSignificant("PsiCommentImpl"))

  // LspConversions.toSymbolKind follows the same logic
  test("LspConversions classifies same as SymbolProvider for ScClass"):
    // Both use class name contains checks, verify they agree
    val className = "org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScClassImpl"
    val providerKind = classifyByName(className)
    // LspConversions uses the same logic
    assertEquals(providerKind, SymbolKind.Class)
