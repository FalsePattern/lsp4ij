// This is a generated file. Not intended for manual editing.
package com.falsepattern.zigbrains.zig.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.Nullable;

public interface ZigLoopStatement extends PsiElement {

  @Nullable
  ZigForStatement getForStatement();

  @Nullable
  ZigWhileStatement getWhileStatement();

  @Nullable
  PsiElement getKeywordInline();

}
