// This is a generated file. Not intended for manual editing.
package com.falsepattern.zigbrains.zig.psi;

import com.intellij.psi.PsiElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public interface ZigAsmInput extends PsiElement {

  @Nullable
  ZigAsmClobbers getAsmClobbers();

  @NotNull
  ZigAsmInputList getAsmInputList();

  @NotNull
  PsiElement getColon();

}
