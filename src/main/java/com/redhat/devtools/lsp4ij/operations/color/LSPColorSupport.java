/*******************************************************************************
 * Copyright (c) 2024 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.lsp4ij.operations.color;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.LanguageServerItem;
import com.redhat.devtools.lsp4ij.LanguageServiceAccessor;
import com.redhat.devtools.lsp4ij.internal.CancellationSupport;
import com.redhat.devtools.lsp4ij.internal.CompletableFutures;
import com.redhat.devtools.lsp4ij.operations.AbstractLSPFeatureSupport;
import org.eclipse.lsp4j.DocumentColorParams;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * LSP color support which loads and caches color information by consuming
 * <ul>
 *     <li>LSP requests textDocument/colorInformation</li>
 * </ul>
 */
public class LSPColorSupport extends AbstractLSPFeatureSupport<DocumentColorParams, List<ColorData>> {
    private final DocumentColorParams params;

    public LSPColorSupport(@NotNull PsiFile file) {
        super(file);
        this.params = new DocumentColorParams(LSPIJUtils.toTextDocumentIdentifier(file.getVirtualFile()));
    }

    public CompletableFuture<List<ColorData>> getColors() {
        return super.getFeatureData(params);
    }

    @Override
    protected CompletableFuture<List<ColorData>> doLoad(DocumentColorParams params, CancellationSupport cancellationSupport) {
        PsiFile file = super.getFile();
        return getColors(file.getVirtualFile(), file.getProject(), params, cancellationSupport);
    }

    private static @NotNull CompletableFuture<List<ColorData>> getColors(@NotNull VirtualFile file,
                                                                         @NotNull Project project,
                                                                         @NotNull DocumentColorParams params,
                                                                         @NotNull CancellationSupport cancellationSupport) {

        return LanguageServiceAccessor.getInstance(project)
                .getLanguageServers(file, LanguageServerItem::isColorSupported)
                .thenComposeAsync(languageServers -> {
                    // Here languageServers is the list of language servers which matches the given file
                    // and which have color capability
                    if (languageServers.isEmpty()) {
                        return CompletableFuture.completedStage(Collections.emptyList());
                    }

                    // Collect list of textDocument/colorInformation future for each language servers
                    List<CompletableFuture<List<ColorData>>> colorInformationPerServerFutures = languageServers
                            .stream()
                            .map(languageServer -> getColorsFor(params, languageServer, cancellationSupport))
                            .toList();

                    // Merge list of textDocument/colorInformation future in one future which return the list of color information
                    return CompletableFutures.mergeInOneFuture(colorInformationPerServerFutures, cancellationSupport);

                });
    }

    private static CompletableFuture<List<ColorData>> getColorsFor(DocumentColorParams params, LanguageServerItem languageServer, CancellationSupport cancellationSupport) {
        return cancellationSupport.execute(languageServer.getTextDocumentService().documentColor(params))
                .thenApplyAsync(colorInformation -> {
                    if (colorInformation == null) {
                        // textDocument/codeLens may return null
                        return Collections.emptyList();
                    }
                    List<ColorData> data = new ArrayList<>();
                    colorInformation.stream()
                            .filter(Objects::nonNull)
                            .forEach(color -> data.add(new ColorData(color, languageServer)));
                    return data;
                });
    }


}
