package com.intellij.codeInsight.completion;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.TailType;
import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupItem;
import com.intellij.featureStatistics.FeatureUsageTracker;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.NotNullLazyValue;
import com.intellij.patterns.ElementPattern;
import static com.intellij.patterns.PlatformPatterns.character;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.filters.TrueFilter;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class CompletionUtil {
  public static final Key<TailType> TAIL_TYPE_ATTR = LookupItem.TAIL_TYPE_ATTR;

  private static final CompletionData ourGenericCompletionData = new CompletionData() {
    {
      final CompletionVariant variant = new CompletionVariant(PsiElement.class, TrueFilter.INSTANCE);
      variant.addCompletionFilter(TrueFilter.INSTANCE, TailType.NONE);
      registerVariant(variant);
    }
  };
  private static final HashMap<FileType, NotNullLazyValue<CompletionData>> ourCustomCompletionDatas = new HashMap<FileType, NotNullLazyValue<CompletionData>>();

  public static final @NonNls String DUMMY_IDENTIFIER = CompletionInitializationContext.DUMMY_IDENTIFIER;
  public static final @NonNls String DUMMY_IDENTIFIER_TRIMMED = DUMMY_IDENTIFIER.trim();
  public static final Key<PsiElement> ORIGINAL_KEY = Key.create("ORIGINAL_KEY");

  @NotNull
  public static <T extends PsiElement> T getOriginalElement(@NotNull T element) {
    final T data = (T)element.getCopyableUserData(ORIGINAL_KEY);
    return data != null ? data : element;
  }

  public static boolean startsWith(String text, String prefix) {
    //if (text.length() <= prefix.length()) return false;
    return toLowerCase(text).startsWith(toLowerCase(prefix));
  }

  private static String toLowerCase(String text) {
    CodeInsightSettings settings = CodeInsightSettings.getInstance();
    switch (settings.COMPLETION_CASE_SENSITIVE) {
      case CodeInsightSettings.NONE:
        return text.toLowerCase();

      case CodeInsightSettings.FIRST_LETTER: {
        StringBuffer buffer = new StringBuffer();
        buffer.append(text.toLowerCase());
        if (buffer.length() > 0) {
          buffer.setCharAt(0, text.charAt(0));
        }
        return buffer.toString();
      }

      default:
        return text;
    }
  }

  public static CompletionData getCompletionDataByElement(final PsiFile file) {

    final CompletionData mainData = getCompletionDataByFileType(file.getFileType());
    return mainData != null ? mainData : ourGenericCompletionData;
  }

  public static void registerCompletionData(FileType fileType, NotNullLazyValue<CompletionData> completionData) {
    ourCustomCompletionDatas.put(fileType, completionData);
  }
  
  public static void registerCompletionData(FileType fileType, final CompletionData completionData) {
    registerCompletionData(fileType, new NotNullLazyValue<CompletionData>() {
      @NotNull
      protected CompletionData compute() {
        return completionData;
      }
    });
  }

  public static CompletionData getCompletionDataByFileType(FileType fileType) {
    for(CompletionDataEP ep: Extensions.getExtensions(CompletionDataEP.EP_NAME)) {
      if (ep.fileType.equals(fileType.getName())) {
        return ep.getHandler();
      }
    }
    final NotNullLazyValue<CompletionData> lazyValue = ourCustomCompletionDatas.get(fileType);
    return lazyValue == null ? null : lazyValue.getValue();
  }


  static boolean isOverwrite(final LookupElement item, final char completionChar) {
    return completionChar != Lookup.AUTO_INSERT_SELECT_CHAR
      ? completionChar == Lookup.REPLACE_SELECT_CHAR
      : item instanceof LookupItem && ((LookupItem)item).getAttribute(LookupItem.OVERWRITE_ON_AUTOCOMPLETE_ATTR) != null;
  }


  public static boolean shouldShowFeature(final CompletionParameters parameters, @NonNls final String id) {
    return FeatureUsageTracker.getInstance().isToBeShown(id, parameters.getPosition().getProject());
  }

  public static String findJavaIdentifierPrefix(final PsiElement insertedElement, final int offset) {
    return findIdentifierPrefix(insertedElement, offset, character().javaIdentifierPart(), character().javaIdentifierStart());
  }

  public static String findIdentifierPrefix(PsiElement insertedElement, int offset, ElementPattern<Character> idPart,
                                             ElementPattern<Character> idStart) {
    if(insertedElement == null) return "";
    final String text = insertedElement.getText();
    final int offsetInElement = offset - insertedElement.getTextRange().getStartOffset();
    int start = offsetInElement - 1;
    while (start >=0 ) {
      if (!idPart.accepts(text.charAt(start))) break;
      --start;
    }
    while (start + 1 < offsetInElement && !idStart.accepts(text.charAt(start + 1))) {
      start++;
    }

    return text.substring(start + 1, offsetInElement).trim();
  }
}
