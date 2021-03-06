// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.vcs.changes;

import com.intellij.ProjectTopics;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.project.ProjectManagerListener;
import com.intellij.openapi.roots.ModuleRootEvent;
import com.intellij.openapi.roots.ModuleRootListener;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vcs.impl.ProjectLevelVcsManagerImpl;
import com.intellij.openapi.vcs.impl.VcsInitObject;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ReflectionUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.vcsUtil.VcsUtil;
import gnu.trove.TObjectHashingStrategy;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public final class VcsDirtyScopeManagerImpl extends VcsDirtyScopeManager implements Disposable {
  private static final Logger LOG = Logger.getInstance(VcsDirtyScopeManagerImpl.class);

  private final Project myProject;

  @NotNull private DirtBuilder myDirtBuilder = new DirtBuilder();
  @Nullable private DirtBuilder myDirtInProgress;

  private boolean myReady;
  private final Object LOCK = new Object();

  public VcsDirtyScopeManagerImpl(@NotNull Project project) {
    myProject = project;

    MessageBusConnection busConnection = myProject.getMessageBus().connect();
    busConnection.subscribe(ProjectTopics.PROJECT_ROOTS, new ModuleRootListener() {
      @Override
      public void rootsChanged(@NotNull ModuleRootEvent event) {
        ApplicationManager.getApplication().invokeLater(() -> markEverythingDirty(), ModalityState.NON_MODAL, myProject.getDisposed());
      }
    });

    busConnection.subscribe(ProjectManager.TOPIC, new ProjectManagerListener() {
      @Override
      public void projectOpened(@NotNull Project project) {
        if (project == myProject) {
          VcsDirtyScopeManagerImpl.this.projectOpened();
        }
      }
    });
  }

  private static ProjectLevelVcsManager getVcsManager(@NotNull Project project) {
    return ProjectLevelVcsManager.getInstance(project);
  }

  private void projectOpened() {
    ProjectLevelVcsManagerImpl.getInstanceImpl(myProject).addInitializationRequest(VcsInitObject.DIRTY_SCOPE_MANAGER, () -> {
      ReadAction.run(() -> {
        boolean ready = !myProject.isDisposed() && myProject.isOpen();
        synchronized (LOCK) {
          myReady = ready;
        }
        if (ready) {
          VcsDirtyScopeVfsListener.install(myProject);
          markEverythingDirty();
        }
      });
    });
  }

  @Override
  public void markEverythingDirty() {
    if ((!myProject.isOpen()) || myProject.isDisposed() || getVcsManager(myProject).getAllActiveVcss().length == 0) {
      return;
    }

    if (LOG.isDebugEnabled()) {
      LOG.debug("everything dirty: " + findFirstInterestingCallerClass());
    }

    synchronized (LOCK) {
      if (myReady) {
        myDirtBuilder.setEverythingDirty(true);
      }
    }

    ChangeListManager.getInstance(myProject).scheduleUpdate();
  }

  @Override
  public void dispose() {
    synchronized (LOCK) {
      myReady = false;
      myDirtBuilder = new DirtBuilder();
      myDirtInProgress = null;
    }
  }

  @NotNull
  private Map<AbstractVcs, Set<FilePath>> groupByVcs(@Nullable Collection<? extends FilePath> from) {
    if (from == null) return Collections.emptyMap();

    VcsDirtyScopeMap map = new VcsDirtyScopeMap();
    for (FilePath path : from) {
      AbstractVcs vcs = getVcsManager(myProject).getVcsFor(path);
      if (vcs != null) {
        map.add(vcs, path);
      }
    }
    return map.asMap();
  }

  @NotNull
  private Map<AbstractVcs, Set<FilePath>> groupFilesByVcs(@Nullable final Collection<? extends VirtualFile> from) {
    if (from == null) return Collections.emptyMap();

    VcsDirtyScopeMap map = new VcsDirtyScopeMap();
    for (VirtualFile file : from) {
      AbstractVcs vcs = getVcsManager(myProject).getVcsFor(file);
      if (vcs != null) {
        map.add(vcs, VcsUtil.getFilePath(file));
      }
    }
    return map.asMap();
  }

  private void fileVcsPathsDirty(@NotNull Map<AbstractVcs, Set<FilePath>> filesConverted,
                                 @NotNull Map<AbstractVcs, Set<FilePath>> dirsConverted) {
    if (filesConverted.isEmpty() && dirsConverted.isEmpty()) return;

    if (LOG.isDebugEnabled()) {
      LOG.debug(String.format("dirty files: %s; dirty dirs: %s; %s",
                              toString(filesConverted), toString(dirsConverted), findFirstInterestingCallerClass()));
    }

    boolean hasSomethingDirty;
    synchronized (LOCK) {
      if (!myReady) return;
      markDirty(myDirtBuilder, filesConverted, false);
      markDirty(myDirtBuilder, dirsConverted, true);
      hasSomethingDirty = !myDirtBuilder.isEmpty();
    }

    if (hasSomethingDirty) {
      ChangeListManager.getInstance(myProject).scheduleUpdate();
    }
  }

  private static void markDirty(@NotNull DirtBuilder dirtBuilder,
                                @NotNull Map<AbstractVcs, Set<FilePath>> filesOrDirs,
                                boolean recursively) {
    for (AbstractVcs vcs : filesOrDirs.keySet()) {
      for (FilePath path : filesOrDirs.get(vcs)) {
        dirtBuilder.addDirtyFile(vcs, path, recursively);
      }
    }
  }

  @Override
  public void filePathsDirty(@Nullable final Collection<? extends FilePath> filesDirty, @Nullable final Collection<? extends FilePath> dirsRecursivelyDirty) {
    try {
      fileVcsPathsDirty(groupByVcs(filesDirty), groupByVcs(dirsRecursivelyDirty));
    }
    catch (ProcessCanceledException ignore) {
    }
  }

  @Override
  public void filesDirty(@Nullable final Collection<? extends VirtualFile> filesDirty, @Nullable final Collection<? extends VirtualFile> dirsRecursivelyDirty) {
    try {
      fileVcsPathsDirty(groupFilesByVcs(filesDirty), groupFilesByVcs(dirsRecursivelyDirty));
    }
    catch (ProcessCanceledException ignore) {
    }
  }

  @Override
  public void fileDirty(@NotNull final VirtualFile file) {
    fileDirty(VcsUtil.getFilePath(file));
  }

  @Override
  public void fileDirty(@NotNull final FilePath file) {
    filePathsDirty(Collections.singleton(file), null);
  }

  @Override
  public void dirDirtyRecursively(@NotNull final VirtualFile dir) {
    dirDirtyRecursively(VcsUtil.getFilePath(dir));
  }

  @Override
  public void dirDirtyRecursively(@NotNull final FilePath path) {
    filePathsDirty(null, Collections.singleton(path));
  }

  @Override
  @Nullable
  public VcsInvalidated retrieveScopes() {
    DirtBuilder dirtBuilder;
    synchronized (LOCK) {
      if (!myReady) return null;
      dirtBuilder = myDirtBuilder;
      myDirtInProgress = dirtBuilder;
      myDirtBuilder = new DirtBuilder();
    }
    return calculateInvalidated(dirtBuilder);
  }

  @NotNull
  private VcsInvalidated calculateInvalidated(@NotNull DirtBuilder dirt) {
    boolean isEverythingDirty = dirt.isEverythingDirty();
    if (isEverythingDirty) {
      // Mark roots explicitly dirty
      VcsRoot[] roots = getVcsManager(myProject).getAllVcsRoots();
      for (VcsRoot root : roots) {
        AbstractVcs vcs = root.getVcs();
        VirtualFile path = root.getPath();
        if (vcs != null) {
          dirt.addDirtyFile(vcs, VcsUtil.getFilePath(path), true);
        }
      }
    }

    List<VcsDirtyScopeImpl> scopes = dirt.getScopes();
    for (VcsDirtyScopeImpl scope : scopes) {
      scope.pack();
    }

    return new VcsInvalidated(scopes, isEverythingDirty);
  }

  @Override
  public void changesProcessed() {
    synchronized (LOCK) {
      myDirtInProgress = null;
    }
  }

  @NotNull
  @Override
  public Collection<FilePath> whatFilesDirty(@NotNull final Collection<? extends FilePath> files) {
    Collection<FilePath> result = new ArrayList<>();
    synchronized (LOCK) {
      if (!myReady) return Collections.emptyList();

      for (FilePath fp : files) {
        if (myDirtBuilder.isFileDirty(fp) ||
            myDirtInProgress != null && myDirtInProgress.isFileDirty(fp)) {
          result.add(fp);
        }
      }
    }
    return result;
  }

  @NotNull
  private static String toString(@NotNull Map<AbstractVcs, Set<FilePath>> filesByVcs) {
    return StringUtil.join(filesByVcs.keySet(), vcs
      -> vcs.getName() + ": " + StringUtil.join(filesByVcs.get(vcs), path -> path.getPath(), "\n"), "\n");
  }

  @Nullable
  private static Class<?> findFirstInterestingCallerClass() {
    for (int i = 1; i <= 7; i++) {
      Class<?> clazz = ReflectionUtil.findCallerClass(i);
      if (clazz == null || !clazz.getName().contains(VcsDirtyScopeManagerImpl.class.getName())) return clazz;
    }
    return null;
  }

  @NotNull
  public static TObjectHashingStrategy<FilePath> getDirtyScopeHashingStrategy(@NotNull AbstractVcs vcs) {
    return vcs.needsCaseSensitiveDirtyScope() ? ChangesUtil.CASE_SENSITIVE_FILE_PATH_HASHING_STRATEGY
                                              : ContainerUtil.canonicalStrategy();
  }
}
