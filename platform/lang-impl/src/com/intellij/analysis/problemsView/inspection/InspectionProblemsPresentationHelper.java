// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.analysis.problemsView.inspection;

import com.intellij.analysis.problemsView.AnalysisErrorSeverity;
import com.intellij.analysis.problemsView.AnalysisProblem;
import com.intellij.analysis.problemsView.AnalysisProblemsPresentationHelper;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

class InspectionProblemsPresentationHelper extends AnalysisProblemsPresentationHelper {
  // Start with default settings. Once actual settings are loaded `setSettings()` is called.
  @NotNull private InspectionProblemsViewSettings mySettings = new InspectionProblemsViewSettings();

  @Nullable private VirtualFile myCurrentFile;

  public void setSettings(@NotNull final InspectionProblemsViewSettings settings) {
    mySettings = settings;
  }

  @NotNull
  public InspectionProblemsViewSettings getSettings() {
    return mySettings;
  }

  @Override
  public void resetAllFilters() {
    mySettings.showErrors = InspectionProblemsViewSettings.SHOW_ERRORS_DEFAULT;
    mySettings.showWarnings = InspectionProblemsViewSettings.SHOW_WARNINGS_DEFAULT;
    mySettings.showHints = InspectionProblemsViewSettings.SHOW_ERRORS_DEFAULT;

    assert !areFiltersApplied();
  }

  //void updateFromFilterSettingsUI(@NotNull final DartProblemsFilterForm form) {
  //  mySettings.showErrors = form.isShowErrors();
  //  mySettings.showWarnings = form.isShowWarnings();
  //  mySettings.showHints = form.isShowHints();
  //  mySettings.fileFilterMode = form.getFileFilterMode();
  //}
  //
  //void updateFromServerSettingsUI(@NotNull final DartAnalysisServerSettingsForm form) {
  //  mySettings.scopedAnalysisMode = form.getScopeAnalysisMode();
  //}

  @Override
  public boolean areFiltersApplied() {
    if (mySettings.showErrors != InspectionProblemsViewSettings.SHOW_ERRORS_DEFAULT) return true;
    if (mySettings.showWarnings != InspectionProblemsViewSettings.SHOW_WARNINGS_DEFAULT) return true;
    if (mySettings.showHints != InspectionProblemsViewSettings.SHOW_HINTS_DEFAULT) return true;

    return false;
  }

  boolean setCurrentFile(@Nullable final VirtualFile file) {
    if (Comparing.equal(myCurrentFile, file)) {
      return false;
    }
    myCurrentFile = file;
    return true;
  }

  @Override
  public boolean isAutoScrollToSource() {
    return mySettings.autoScrollToSource;
  }

  @Override
  public void setAutoScrollToSource(final boolean autoScroll) {
    mySettings.autoScrollToSource = autoScroll;
  }

  @Override
  public boolean isGroupBySeverity() {
    return mySettings.groupBySeverity;
  }

  @Override
  public void setGroupBySeverity(final boolean groupBySeverity) {
    mySettings.groupBySeverity = groupBySeverity;
  }

  @Override
  public boolean isShowErrors() {
    return mySettings.showErrors;
  }

  @Override
  public boolean isShowWarnings() {
    return mySettings.showWarnings;
  }

  @Override
  public boolean isShowHints() {
    return mySettings.showHints;
  }


  @Override
  @Nullable
  public VirtualFile getCurrentFile() {
    return myCurrentFile;
  }

  @Override
  public boolean shouldShowProblem(@NotNull final AnalysisProblem problem) {
    if (!isShowErrors() && AnalysisErrorSeverity.ERROR.equals(problem.getSeverity())) return false;
    if (!isShowWarnings() && AnalysisErrorSeverity.WARNING.equals(problem.getSeverity())) return false;
    if (!isShowHints() && AnalysisErrorSeverity.INFO.equals(problem.getSeverity())) return false;


    return true;
  }


  @Override
  @NotNull
  public String getFilterTypeText() {
    final StringBuilder builder = new StringBuilder();

    builder.append("filtering by current file");

    if (!isShowErrors() || !isShowWarnings() || !isShowHints()) {
      builder.append(builder.length() == 0 ? "filtering by severity" : " and severity");
    }

    return builder.toString();
  }

  void updateFromFilterSettingsUI(@NotNull InspectionProblemsFilterForm form) {
    mySettings.showErrors = form.isShowErrors();
    mySettings.showWarnings = form.isShowWarnings();
    mySettings.showHints = form.isShowHints();
  }
}
