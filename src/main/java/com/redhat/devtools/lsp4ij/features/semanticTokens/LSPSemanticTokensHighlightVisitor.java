/*******************************************************************************
 * Copyright (c) 2024-2025 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 * FalsePattern - Performance improvements for huge files
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.features.semanticTokens;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.HighlightVisitor;
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.impl.source.tree.LeafElement;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServersRegistry;
import com.redhat.devtools.lsp4ij.client.ExecuteLSPFeatureStatus;
import com.redhat.devtools.lsp4ij.client.indexing.ProjectIndexingManager;
import com.redhat.devtools.lsp4ij.internal.SimpleLanguageUtils;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.isDoneNormally;
import static com.redhat.devtools.lsp4ij.internal.CompletableFutures.waitUntilDone;


/**
 * LSP 'textDocument/semanticTokens support by implementing IntelliJ {@link HighlightVisitor}.
 *
 * <p>
 * Implementing {@link HighlightVisitor} gives the capability to have an existing highlighter (custom highlighter, TextMate highlighter)
 * and add semantic coloration.
 * </p>
 */
@ApiStatus.Internal
public class LSPSemanticTokensHighlightVisitor implements HighlightVisitor {


    private static final Logger LOGGER = LoggerFactory.getLogger(LSPSemanticTokensHighlightVisitor.class);
    ;

    @Override
    public boolean suitableForFile(@NotNull PsiFile file) {
        return LanguageServersRegistry.getInstance().isFileSupported(file);
    }

    private HighlightInfoHolder holder;
    private LazyHighlightInfo[] lazyInfos;
    private AtomicInteger maxOffset;
    private ReentrantLock lock;

    @Override
    public boolean analyze(@NotNull PsiFile file, boolean updateWholeFile, @NotNull HighlightInfoHolder holder, @NotNull Runnable action) {
        if (ProjectIndexingManager.canExecuteLSPFeature(file) != ExecuteLSPFeatureStatus.NOW) {
            return true;
        }
        AtomicBoolean isCancelled = new AtomicBoolean(false);
        CompletableFuture<?> future = null;
        try {
            var lock = new ReentrantLock();
            future = highlightSemanticTokens(file, isCancelled, lock);
            if (future != null) {
                this.lock = lock;
                this.maxOffset = new AtomicInteger(0);
                this.holder = holder;
            } else {
                this.lazyInfos = null;
            }
            action.run();
        } finally {
            isCancelled.set(true);
            this.holder = null;
            this.lazyInfos = null;
            this.maxOffset = null;
            try {
                if (future != null) {
                future.get();
                }
            } catch (InterruptedException | ExecutionException ignored) {
            }
        }
        return true;
    }

    @Override
    public void visit(@NotNull PsiElement element) {
        if (lazyInfos == null || !(element instanceof LeafElement))
            return;
        int start = element.getTextOffset();
        if (start < 0)
            return;
        int end = start + element.getTextLength();
        while (!lock.tryLock()) {
            Thread.yield();
        }
        while (end > maxOffset.get()) {
            lock.unlock();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            while (!lock.tryLock()) {
                Thread.yield();
            }
        }
        try {
            for (int i = start; i < end && i < lazyInfos.length; i++) {
                var info = lazyInfos[i];
                if (info != null) {
                    holder.add(info.resolve(i));
                    lazyInfos[i] = null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private CompletableFuture<?> highlightSemanticTokens(@NotNull PsiFile file, AtomicBoolean isCancelled, ReentrantLock lock) {
        // Consume LSP 'textDocument/semanticTokens/full' request
        LSPSemanticTokensSupport semanticTokensSupport = LSPFileSupport.getSupport(file).getSemanticTokensSupport();
        var params = new SemanticTokensParams(LSPIJUtils.toTextDocumentIdentifier(file.getVirtualFile()));
        CompletableFuture<SemanticTokensData> semanticTokensFuture = semanticTokensSupport.getSemanticTokens(params);

        try {
            waitUntilDone(semanticTokensFuture, file, 25);
        } catch (
                ProcessCanceledException e) {//Since 2024.2 ProcessCanceledException extends CancellationException so we can't use multicatch to keep backward compatibility
            //TODO delete block when minimum required version is 2024.2
            semanticTokensSupport.cancel();
            return null;
        } catch (CancellationException e) {
            // cancel the LSP requests textDocument/semanticTokens/full
            semanticTokensSupport.cancel();
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("Error while consuming LSP 'textDocument/semanticTokens/full' request", e);
            return null;
        } catch (TimeoutException e) {
            semanticTokensFuture.thenRun(() -> {
                var project = file.getProject();
                if (project.isDisposed()) {
                    return;
                }
                DaemonCodeAnalyzer.getInstance(project).restart(file);
            });
            return null;
        }

        if (isDoneNormally(semanticTokensFuture)) {
            // textDocument/semanticTokens/full has been collected correctly, create list of IJ HighlightInfo from LSP SemanticTokens data
            SemanticTokensData semanticTokens = semanticTokensFuture.getNow(null);
            if (semanticTokens != null) {
                var document = LSPIJUtils.getDocument(file.getVirtualFile());
                if (document == null) {
                    return null;
                }
                var infos = new LazyHighlightInfo[document.getTextLength()];
                this.lazyInfos = infos;
                return CompletableFuture.runAsync(() -> {
                    semanticTokens.highlight(file, document, (start, end, colorKey) -> {
                        var offsetAtomic = maxOffset;
                        if (offsetAtomic == null) {
                            return;
                        }
                        lock.lock();
                        try {
                            infos[start] = new LazyHighlightInfo(end, colorKey);
                            offsetAtomic.set(start + end);
                        } finally {
                            lock.unlock();
                        }
                        }, () -> {
                        if (isCancelled.get()) {
                            throw new ProcessCanceledException();
                        }
                    });
                    var offsetAtomic = maxOffset;
                    if (offsetAtomic != null) {
                        offsetAtomic.set(Integer.MAX_VALUE);
                    }
                });
            }
        }
        return null;
    }

    @Override
    public @NotNull HighlightVisitor clone() {
        return new LSPSemanticTokensHighlightVisitor();
    }

}
