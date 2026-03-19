package org.jetbrains.scalalsP.intellij

import org.junit.Assert.*
import org.junit.Test
import org.eclipse.lsp4j.SymbolKind

class SymbolKindTest:

  private def classifyByName(className: String): SymbolKind =
    if className.contains("ScClass") || className.contains("PsiClass") then SymbolKind.Class
    else if className.contains("ScTrait") then SymbolKind.Interface
    else if className.contains("ScObject") then SymbolKind.Module
    else if className.contains("ScFunction") || className.contains("PsiMethod") then SymbolKind.Method
    else if className.contains("ScVariable") then SymbolKind.Variable
    else if className.contains("ScValue") || className.contains("PsiField") then SymbolKind.Field
    else if className.contains("ScTypeAlias") then SymbolKind.TypeParameter
    else if className.contains("ScPackaging") then SymbolKind.Package
    else SymbolKind.Variable

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

  @Test def testScClassMapsToClass(): Unit =
    assertEquals(SymbolKind.Class, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScClassImpl"))

  @Test def testScTraitMapsToInterface(): Unit =
    assertEquals(SymbolKind.Interface, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScTraitImpl"))

  @Test def testScObjectMapsToModule(): Unit =
    assertEquals(SymbolKind.Module, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.toplevel.typedef.ScObjectImpl"))

  @Test def testScFunctionMapsToMethod(): Unit =
    assertEquals(SymbolKind.Method, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScFunctionDefinitionImpl"))

  @Test def testScValueMapsToField(): Unit =
    assertEquals(SymbolKind.Field, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScValueDeclarationImpl"))

  @Test def testScVariableMapsToVariable(): Unit =
    assertEquals(SymbolKind.Variable, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScVariableDefinitionImpl"))

  @Test def testScTypeAliasMapsToTypeParameter(): Unit =
    assertEquals(SymbolKind.TypeParameter, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.statements.ScTypeAliasDefinitionImpl"))

  @Test def testScPackagingMapsToPackage(): Unit =
    assertEquals(SymbolKind.Package, classifyByName("org.jetbrains.plugins.scala.lang.psi.impl.ScPackagingImpl"))

  @Test def testPsiClassMapsToClass(): Unit =
    assertEquals(SymbolKind.Class, classifyByName("com.intellij.psi.impl.source.PsiClassImpl"))

  @Test def testPsiMethodMapsToMethod(): Unit =
    assertEquals(SymbolKind.Method, classifyByName("com.intellij.psi.impl.source.PsiMethodImpl"))

  @Test def testUnknownElementMapsToVariable(): Unit =
    assertEquals(SymbolKind.Variable, classifyByName("org.jetbrains.something.Unknown"))

  @Test def testScTypeDefinitionIsSignificant(): Unit =
    assertTrue(isSignificant("ScTypeDefinitionImpl"))

  @Test def testScClassIsSignificant(): Unit =
    assertTrue(isSignificant("ScClassImpl"))

  @Test def testScFunctionIsSignificant(): Unit =
    assertTrue(isSignificant("ScFunctionImpl"))

  @Test def testScValueIsSignificant(): Unit =
    assertTrue(isSignificant("ScValueDeclarationImpl"))

  @Test def testScVariableIsSignificant(): Unit =
    assertTrue(isSignificant("ScVariableDefinitionImpl"))

  @Test def testScTypeAliasIsSignificant(): Unit =
    assertTrue(isSignificant("ScTypeAliasDefinitionImpl"))

  @Test def testRandomElementIsNotSignificant(): Unit =
    assertFalse(isSignificant("ScImportExprImpl"))

  @Test def testScReferenceIsNotSignificant(): Unit =
    assertFalse(isSignificant("ScReferenceExpressionImpl"))

  @Test def testWhitespaceCommentPsiNodesAreNotSignificant(): Unit =
    assertFalse(isSignificant("PsiWhiteSpaceImpl"))
    assertFalse(isSignificant("PsiCommentImpl"))
