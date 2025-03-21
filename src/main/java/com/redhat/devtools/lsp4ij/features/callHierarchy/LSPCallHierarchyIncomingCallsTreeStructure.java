/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and declaration
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.callHierarchy;

import com.intellij.ide.hierarchy.HierarchyNodeDescriptor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.features.hierarchy.LSPHierarchyNodeDescriptor;
import org.eclipse.lsp4j.CallHierarchyItem;
import org.eclipse.lsp4j.CallHierarchyIncomingCallsParams;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.waitUntilDone;

/**
 * LSP call hierarchy tree structure base for callHierarchy/incomingCalls.
 */
public class LSPCallHierarchyIncomingCallsTreeStructure extends LSPCallHierarchyTreeStructureBase {

    private static final Logger LOGGER = LoggerFactory.getLogger(LSPCallHierarchyIncomingCallsTreeStructure.class);

    public LSPCallHierarchyIncomingCallsTreeStructure(@NotNull Project project, @NotNull PsiElement psiElement) {
        super(project, psiElement);
    }

    @Override
    protected void buildChildren(@NotNull HierarchyNodeDescriptor descriptor,
                                 @NotNull PsiFile psiFile,
                                 @Nullable CallHierarchyItem hierarchyItem,
                                 @NotNull List<LSPHierarchyNodeDescriptor> descriptors) {
        LSPCallHierarchyIncomingCallsSupport callHierarchyIncomingCallsSupport = LSPFileSupport.getSupport(psiFile).getCallHierarchyIncomingCallsSupport();
        callHierarchyIncomingCallsSupport.cancel();
        var params = new CallHierarchyIncomingCallsParams(hierarchyItem);
        CompletableFuture<List<CallHierarchyItemData>> prepareCallHierarchyFuture = callHierarchyIncomingCallsSupport.getCallHierarchyIncomingCalls(params);
        try {
            waitUntilDone(prepareCallHierarchyFuture, psiFile);
        } catch (ProcessCanceledException ex) {
            // cancel the LSP requests callHierarchy/incomingCalls
            callHierarchyIncomingCallsSupport.cancel();
        } catch (CancellationException ex) {
            // cancel the LSP requests callHierarchy/incomingCalls
            callHierarchyIncomingCallsSupport.cancel();
        } catch (ExecutionException e) {
            LOGGER.error("Error while consuming LSP 'callHierarchy/incomingCalls' request", e);
        }
        fillChildren(descriptor, prepareCallHierarchyFuture, descriptors);
    }

}
