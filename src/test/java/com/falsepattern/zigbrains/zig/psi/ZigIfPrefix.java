// This is a generated file. Not intended for manual editing.
package com.falsepattern.zigbrains.zig.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ZigIfPrefix extends PsiElement {

  @Nullable
  ZigExpr getExpr();

  @Nullable
  ZigPtrPayload getPtrPayload();

  @NotNull
  PsiElement getKeywordIf();

  @Nullable
  PsiElement getLparen();

  @Nullable
  PsiElement getRparen();

}
