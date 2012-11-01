package com.intellij.flex.build;

import com.intellij.flex.FlexCommonBundle;
import com.intellij.flex.FlexCommonUtils;
import com.intellij.flex.model.JpsFlexProjectLevelCompilerOptionsExtension;
import com.intellij.flex.model.bc.*;
import com.intellij.flex.model.sdk.JpsFlexmojosSdkType;
import com.intellij.flex.model.sdk.RslUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.JDOMUtil;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.PairConsumer;
import com.intellij.util.PathUtilRt;
import com.intellij.util.Processor;
import com.intellij.util.SystemProperties;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jps.incremental.Utils;
import org.jetbrains.jps.model.java.JavaSourceRootType;
import org.jetbrains.jps.model.java.JpsJavaExtensionService;
import org.jetbrains.jps.model.java.compiler.JpsCompilerExcludes;
import org.jetbrains.jps.model.library.JpsLibrary;
import org.jetbrains.jps.model.library.JpsOrderRootType;
import org.jetbrains.jps.model.library.sdk.JpsSdk;
import org.jetbrains.jps.model.module.JpsModule;
import org.jetbrains.jps.model.module.JpsModuleSourceRoot;
import org.jetbrains.jps.model.module.JpsTypedModuleSourceRoot;
import org.jetbrains.jps.util.JpsPathUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

public class CompilerConfigGeneratorRt {

  private static final String[] LIB_ORDER =
    {"framework", "textLayout", "osmf", "spark", "sparkskins", "rpc", "charts", "spark_dmv", "mx", "advancedgrids"};

  private final JpsModule myModule;
  private final JpsFlexBuildConfiguration myBC;
  private final boolean myFlexUnit;
  private final boolean myCSS;
  private final JpsSdk<?> mySdk;
  private final boolean myFlexmojos;
  private final JpsFlexModuleOrProjectCompilerOptions myModuleLevelCompilerOptions;
  private final JpsFlexModuleOrProjectCompilerOptions myProjectLevelCompilerOptions;

  private CompilerConfigGeneratorRt(final @NotNull JpsFlexBuildConfiguration bc,
                                    final @NotNull JpsFlexModuleOrProjectCompilerOptions moduleLevelCompilerOptions,
                                    final @NotNull JpsFlexModuleOrProjectCompilerOptions projectLevelCompilerOptions) throws IOException {
    myModule = bc.getModule();
    myBC = bc;
    myFlexUnit = FlexCommonUtils.isFlexUnitBC(myBC);
    myCSS = FlexCommonUtils.isRuntimeStyleSheetBC(bc);
    mySdk = bc.getSdk();
    if (mySdk == null) {
      throw new IOException(FlexCommonBundle.message("sdk.not.set.for.bc.0.of.module.1", bc.getName(), bc.getModule().getName()));
    }
    myFlexmojos = mySdk.getSdkType() == JpsFlexmojosSdkType.INSTANCE;
    myModuleLevelCompilerOptions = moduleLevelCompilerOptions;
    myProjectLevelCompilerOptions = projectLevelCompilerOptions;
  }

  public static File getOrCreateConfigFile(final JpsFlexBuildConfiguration bc) throws IOException {
    final CompilerConfigGeneratorRt generator =
      new CompilerConfigGeneratorRt(bc,
                                    bc.getModule().getProperties().getModuleLevelCompilerOptions(),
                                    JpsFlexProjectLevelCompilerOptionsExtension
                                      .getProjectLevelCompilerOptions(bc.getModule().getProject()));
    String text = generator.generateConfigFileText();

    if (bc.isTempBCForCompilation()) {
      final JpsFlexBuildConfiguration originalBC = bc.getModule().getProperties().findConfigurationByName(bc.getName());
      final boolean makeExternalLibsMerged =
        FlexCommonUtils.isFlexUnitBC(bc) || (originalBC != null && originalBC.getOutputType() == OutputType.Library);
      final boolean makeIncludedLibsMerged = FlexCommonUtils.isRuntimeStyleSheetBC(bc);
      text = FlexCompilerConfigFileUtilBase.mergeWithCustomConfigFile(text, bc.getCompilerOptions().getAdditionalConfigFilePath(),
                                                                      makeExternalLibsMerged, makeIncludedLibsMerged);
    }

    final String name = getConfigFileName(bc, FlexCommonUtils.getBCSpecifier(bc));
    return getOrCreateConfigFile(name, text);
  }

  public static String getSwfVersionForTargetPlayer(final String targetPlayer) {
    if (StringUtil.compareVersionNumbers(targetPlayer, "11.5") >= 0) return "18";
    if (StringUtil.compareVersionNumbers(targetPlayer, "11.4") >= 0) return "17";
    if (StringUtil.compareVersionNumbers(targetPlayer, "11.3") >= 0) return "16";
    if (StringUtil.compareVersionNumbers(targetPlayer, "11.2") >= 0) return "15";
    if (StringUtil.compareVersionNumbers(targetPlayer, "11.1") >= 0) return "14";
    if (StringUtil.compareVersionNumbers(targetPlayer, "11") >= 0) return "13";
    if (StringUtil.compareVersionNumbers(targetPlayer, "10.3") >= 0) return "12";
    if (StringUtil.compareVersionNumbers(targetPlayer, "10.2") >= 0) return "11";
    if (StringUtil.compareVersionNumbers(targetPlayer, "10.1") >= 0) return "10";
    return "9";
  }

  public static String getSwfVersionForSdk(final String sdkVersion) {
    if (StringUtil.compareVersionNumbers(sdkVersion, "4.6") >= 0) return "14";
    if (StringUtil.compareVersionNumbers(sdkVersion, "4.5") >= 0) return "11";
    assert false : sdkVersion;
    return null;
  }

  private String generateConfigFileText() throws IOException {
    final Element rootElement =
      new Element(FlexCompilerConfigFileUtilBase.FLEX_CONFIG, "http://www.adobe.com/2006/flex-config");

    addMandatoryOptions(rootElement);
    addSourcePaths(rootElement);
    if (!myFlexmojos) {
      handleOptionsWithSpecialValues(rootElement);
      addNamespaces(rootElement);
      addRootsFromSdk(rootElement);
    }
    addLibs(rootElement);
    addOtherOptions(rootElement);
    addInputOutputPaths(rootElement);

    return JDOMUtil.writeElement(rootElement, "\n");
  }

  private void addMandatoryOptions(final Element rootElement) {
    if (!FlexCommonUtils.isRLMTemporaryBC(myBC) && !FlexCommonUtils.isRuntimeStyleSheetBC(myBC) &&
        FlexCommonUtils.canHaveRLMsAndRuntimeStylesheets(myBC) && myBC.getRLMs().size() > 0) {
      addOption(rootElement, CompilerOptionInfo.LINK_REPORT_INFO, getLinkReportFilePath(myBC));
    }

    if (FlexCommonUtils.isRLMTemporaryBC(myBC) && !myBC.getOptimizeFor().isEmpty()) {
      final String customLinkReportPath = getCustomLinkReportPath(myBC);
      final String linkReportPath = StringUtil.notNullize(customLinkReportPath, getLinkReportFilePath(myBC));
      addOption(rootElement, CompilerOptionInfo.LOAD_EXTERNS_INFO, linkReportPath);
    }

    addOption(rootElement, CompilerOptionInfo.WARN_NO_CONSTRUCTOR_INFO, "false");
    if (myFlexmojos) return;

    final BuildConfigurationNature nature = myBC.getNature();
    final String targetPlayer = nature.isWebPlatform()
                                ? myBC.getDependencies().getTargetPlayer()
                                : FlexCommonUtils.getMaximumTargetPlayer(mySdk.getHomePath());
    addOption(rootElement, CompilerOptionInfo.TARGET_PLAYER_INFO, targetPlayer);

    if (StringUtil.compareVersionNumbers(mySdk.getVersionString(), "4.5") >= 0) {
      final String swfVersion = nature.isWebPlatform() ? getSwfVersionForTargetPlayer(targetPlayer)
                                                       : getSwfVersionForSdk(mySdk.getVersionString());
      addOption(rootElement, CompilerOptionInfo.SWF_VERSION_INFO, swfVersion);
    }

    if (nature.isMobilePlatform()) {
      addOption(rootElement, CompilerOptionInfo.MOBILE_INFO, "true");
      addOption(rootElement, CompilerOptionInfo.PRELOADER_INFO, "spark.preloaders.SplashScreen");
    }

    final String accessible = nature.isMobilePlatform() ? "false"
                                                        : StringUtil.compareVersionNumbers(mySdk.getVersionString(), "4") >= 0 ? "true"
                                                                                                                               : "false";
    addOption(rootElement, CompilerOptionInfo.ACCESSIBLE_INFO, accessible);

    final String fontManagers = StringUtil.compareVersionNumbers(mySdk.getVersionString(), "4") >= 0
                                ? "flash.fonts.JREFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.BatikFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.AFEFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.CFFFontManager"

                                : "flash.fonts.JREFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.AFEFontManager" + CompilerOptionInfo.LIST_ENTRIES_SEPARATOR +
                                  "flash.fonts.BatikFontManager";
    addOption(rootElement, CompilerOptionInfo.FONT_MANAGERS_INFO, fontManagers);

    addOption(rootElement, CompilerOptionInfo.STATIC_RSLS_INFO, "false");
  }

  @Nullable
  private static String getCustomLinkReportPath(final JpsFlexBuildConfiguration rlmBC) {
    final JpsFlexBuildConfiguration appBC = rlmBC.getModule().getProperties().findConfigurationByName(rlmBC.getName());
    if (appBC != null) {
      final List<String> linkReports = FlexCommonUtils.getOptionValues(appBC.getCompilerOptions().getAdditionalOptions(), "link-report");
      if (!linkReports.isEmpty()) {
        final String path = linkReports.get(0);
        if (new File(path).isFile()) return path;
        final String absPath = FlexCommonUtils.getFlexCompilerWorkDirPath(appBC.getModule().getProject()) + "/" + path;
        if (new File(absPath).isFile()) return absPath;
      }
      else {
        final String configFilePath = appBC.getCompilerOptions().getAdditionalConfigFilePath();
        if (!configFilePath.isEmpty()) {
          final File configFile = new File(configFilePath);
          if (configFile.isFile()) {
            final String path = FlexCommonUtils.findXMLElement(configFile, "<flex-config><link-report>");
            if (path != null) {
              if (new File(path).isFile()) return path;
              // I have no idea why Flex compiler treats path relative to source root for "link-report" option
              for (JpsModuleSourceRoot srcRoot : appBC.getModule().getSourceRoots(JavaSourceRootType.SOURCE)) {
                final String absPath = srcRoot.getFile().getPath() + "/" + path;
                if (new File(absPath).isFile()) return absPath;
              }
            }
          }
        }
      }
    }

    return null;
  }

  /**
   * Adds options that get incorrect default values inside compiler code if not set explicitly.
   */
  private void handleOptionsWithSpecialValues(final Element rootElement) {
    for (final CompilerOptionInfo info : CompilerOptionInfo.getOptionsWithSpecialValues()) {
      final Pair<String, ValueSource> valueAndSource = getValueAndSource(info);
      final boolean themeForPureAS = myBC.isPureAs() && "compiler.theme".equals(info.ID);
      if (valueAndSource.second == ValueSource.GlobalDefault && (!valueAndSource.first.isEmpty() || themeForPureAS)) {
        // do not add empty preloader to Web/Desktop, let compiler take default itself (mx.preloaders.SparkDownloadProgressBar when -compatibility-version >= 4.0 and mx.preloaders.DownloadProgressBar when -compatibility-version < 4.0)
        addOption(rootElement, info, valueAndSource.first);
      }
    }
  }

  private void addNamespaces(final Element rootElement) {
    final StringBuilder namespaceBuilder = new StringBuilder();
    FlexCommonUtils.processStandardNamespaces(myBC, new PairConsumer<String, String>() {
      @Override
      public void consume(final String namespace, final String relativePath) {
        if (namespaceBuilder.length() > 0) {
          namespaceBuilder.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
        }
        namespaceBuilder.append(namespace).append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
          .append(CompilerOptionInfo.FLEX_SDK_MACRO + "/").append(relativePath);
      }
    });

    if (namespaceBuilder.length() == 0) return;
    final CompilerOptionInfo info = CompilerOptionInfo.getOptionInfo("compiler.namespaces.namespace");
    addOption(rootElement, info, namespaceBuilder.toString());
  }

  private void addRootsFromSdk(final Element rootElement) {
    final CompilerOptionInfo localeInfo = CompilerOptionInfo.getOptionInfo("compiler.locale");
    if (!getValueAndSource(localeInfo).first.isEmpty()) {
      addOption(rootElement, CompilerOptionInfo.LIBRARY_PATH_INFO, mySdk.getHomePath() + "/frameworks/locale/{locale}");
    }

    final Map<String, String> libNameToRslInfo = new THashMap<String, String>();

    for (final String swcUrl : mySdk.getParent().getRootUrls(JpsOrderRootType.COMPILED)) {
      final String swcPath = JpsPathUtil.urlToPath(swcUrl);
      if (!swcPath.toLowerCase().endsWith(".swc")) {
        Logger.getInstance(CompilerConfigGeneratorRt.class.getName()).warn("Unexpected URL in Flex SDK classes: " + swcUrl);
        continue;
      }

      LinkageType linkageType = FlexCommonUtils.getSdkEntryLinkageType(swcPath, myBC);

      // check applicability
      if (linkageType == null) continue;
      // resolve default
      if (linkageType == LinkageType.Default) linkageType = myBC.getDependencies().getFrameworkLinkage();
      if (linkageType == LinkageType.Default) {
        linkageType = FlexCommonUtils.getDefaultFrameworkLinkage(mySdk.getVersionString(), myBC.getNature());
      }
      if (myCSS && linkageType == LinkageType.Include) linkageType = LinkageType.Merged;

      final CompilerOptionInfo info = linkageType == LinkageType.Merged ? CompilerOptionInfo.LIBRARY_PATH_INFO :
                                      linkageType == LinkageType.RSL ? CompilerOptionInfo.LIBRARY_PATH_INFO :
                                      linkageType == LinkageType.External ? CompilerOptionInfo.EXTERNAL_LIBRARY_INFO :
                                      linkageType == LinkageType.Include ? CompilerOptionInfo.INCLUDE_LIBRARY_INFO :
                                      null;

      assert info != null : swcPath + ": " + linkageType.getShortText();

      addOption(rootElement, info, swcPath);

      if (linkageType == LinkageType.RSL) {
        final List<String> rslUrls = RslUtil.getRslUrls(mySdk.getHomePath(), swcPath);
        if (rslUrls.isEmpty()) continue;

        final StringBuilder rslBuilder = new StringBuilder();
        final String firstUrl = rslUrls.get(0);
        rslBuilder
          .append(swcPath)
          .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
          .append(firstUrl)
          .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR);
        if (firstUrl.startsWith("http://")) {
          rslBuilder.append("http://fpdownload.adobe.com/pub/swz/crossdomain.xml");
        }

        if (rslUrls.size() > 1) {
          final String secondUrl = rslUrls.get(1);
          rslBuilder
            .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR)
            .append(secondUrl)
            .append(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR);
          if (secondUrl.startsWith("http://")) {
            rslBuilder.append("http://fpdownload.adobe.com/pub/swz/crossdomain.xml");
          }
        }

        final String swcName = PathUtilRt.getFileName(swcPath);
        final String libName = swcName.substring(0, swcName.length() - ".swc".length());
        libNameToRslInfo.put(libName, rslBuilder.toString());
      }
    }

    addRslInfo(rootElement, libNameToRslInfo);
  }

  private void addRslInfo(final Element rootElement, final Map<String, String> libNameToRslInfo) {
    if (libNameToRslInfo.isEmpty()) return;

    // RSL order is important!
    for (final String libName : LIB_ORDER) {
      final String rslInfo = libNameToRslInfo.remove(libName);
      if (rslInfo != null) {
        final CompilerOptionInfo option =
          StringUtil.split(rslInfo, CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR, true, false).size() == 3
          ? CompilerOptionInfo.RSL_ONE_URL_PATH_INFO
          : CompilerOptionInfo.RSL_TWO_URLS_PATH_INFO;
        addOption(rootElement, option, rslInfo);
      }
    }

    // now add other in random order, though up to Flex SDK 4.5.1 the map should be empty at this stage
    for (final String rslInfo : libNameToRslInfo.values()) {
      final CompilerOptionInfo option =
        StringUtil.split(rslInfo, CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR, true, false).size() == 3
        ? CompilerOptionInfo.RSL_ONE_URL_PATH_INFO
        : CompilerOptionInfo.RSL_TWO_URLS_PATH_INFO;
      addOption(rootElement, option, rslInfo);
    }
  }

  private void addLibs(final Element rootElement) {
    for (final JpsFlexDependencyEntry entry : myBC.getDependencies().getEntries()) {
      LinkageType linkageType = entry.getLinkageType();
      if (linkageType == LinkageType.Test) {
        if (myFlexUnit) {
          linkageType = LinkageType.Merged;
        }
        else {
          continue;
        }
      }
      if (myCSS && linkageType == LinkageType.Include) linkageType = LinkageType.Merged;

      if (entry instanceof JpsFlexBCDependencyEntry) {
        if (linkageType == LinkageType.LoadInRuntime) continue;

        final JpsFlexBuildConfiguration dependencyBC = ((JpsFlexBCDependencyEntry)entry).getBC();
        if (dependencyBC != null && FlexCommonUtils.checkDependencyType(myBC.getOutputType(), dependencyBC.getOutputType(), linkageType)) {
          addLib(rootElement, dependencyBC.getActualOutputFilePath(), linkageType);
        }
      }
      else if (entry instanceof JpsLibraryDependencyEntry) {
        final JpsLibrary library = ((JpsLibraryDependencyEntry)entry).getLibrary();
        if (library != null) {
          addLibraryRoots(rootElement, library.getRootUrls(JpsOrderRootType.COMPILED), linkageType);
        }
      }
    }

    if (myFlexUnit) {
      final String unitTestingSupportSwc = FlexCommonUtils
        .getPathToBundledJar(FlexCommonUtils.getFlexUnitSupportLibName(myBC.getNature(), myBC.getDependencies().getComponentSet()));
      addLibraryRoots(rootElement, Collections.singletonList(JpsPathUtil.pathToUrl(unitTestingSupportSwc)), LinkageType.Merged);
    }
  }

  private void addLibraryRoots(final Element rootElement, final List<String> libRootUrls, final LinkageType linkageType) {
    for (String libRootUrl : libRootUrls) {
      final String libFilePath = JpsPathUtil.urlToPath(libRootUrl);
      final File libFile = new File(libFilePath);

      if (libFile.isDirectory()) {
        addOption(rootElement, CompilerOptionInfo.SOURCE_PATH_INFO, libFile.getPath());
      }
      else if (libFile.isFile()) {
        if (libFilePath.toLowerCase().endsWith(".ane")) {
          addLib(rootElement, libFilePath, LinkageType.External);
        }
        else if (libFilePath.toLowerCase().endsWith(".swc")) {
          // "airglobal.swc" and "playerglobal.swc" file names are hardcoded in Flex compiler
          // including libraries like "playerglobal-3.5.0.12683-9.swc" may lead to error at runtime like "VerifyError Error #1079: Native methods are not allowed in loaded code."
          // so here we just skip including such libraries in config file.
          // Compilation should be ok because base flexmojos config file contains correct reference to its copy in target/classes/libraries/playerglobal.swc
          final String libFileName = libFile.getName().toLowerCase();
          if (libFileName.startsWith("airglobal") && !libFileName.equals("airglobal.swc") ||
              libFileName.startsWith("playerglobal") && !libFileName.equals("playerglobal.swc")) {
            continue;
          }

          addLib(rootElement, libFilePath, linkageType);
        }
      }
    }
  }

  private void addLib(final Element rootElement, final String swcPath, final LinkageType linkageType) {
    final CompilerOptionInfo info = linkageType == LinkageType.Merged || linkageType == LinkageType.RSL
                                    ? CompilerOptionInfo.LIBRARY_PATH_INFO
                                    : linkageType == LinkageType.External
                                      ? CompilerOptionInfo.EXTERNAL_LIBRARY_INFO
                                      : linkageType == LinkageType.Include
                                        ? CompilerOptionInfo.INCLUDE_LIBRARY_INFO
                                        : null;
    assert info != null : swcPath + ": " + linkageType;

    addOption(rootElement, info, swcPath);

    if (linkageType == LinkageType.RSL) {
      // todo add RSL URLs
    }
  }

  private void addSourcePaths(final Element rootElement) {
    final String localeValue = getValueAndSource(CompilerOptionInfo.getOptionInfo("compiler.locale")).first;
    final List<String> locales = StringUtil.split(localeValue, CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
    // when adding source paths we respect locales set both in UI and in Additional compiler options
    locales.addAll(FlexCommonUtils.getOptionValues(myProjectLevelCompilerOptions.getAdditionalOptions(), "locale", "compiler.locale"));
    locales.addAll(FlexCommonUtils.getOptionValues(myModuleLevelCompilerOptions.getAdditionalOptions(), "locale", "compiler.locale"));
    locales.addAll(FlexCommonUtils.getOptionValues(myBC.getCompilerOptions().getAdditionalOptions(), "locale", "compiler.locale"));

    final Set<String> sourcePathsWithLocaleToken = new THashSet<String>(); // Set - to avoid duplication of paths like "locale/{locale}"
    final List<String> sourcePathsWithoutLocaleToken = new LinkedList<String>();

    for (JpsModuleSourceRoot srcRoot : myModule.getSourceRoots(JavaSourceRootType.SOURCE)) {
      final String srcRootPath = JpsPathUtil.urlToPath(srcRoot.getUrl());
      if (locales.contains(PathUtilRt.getFileName(srcRootPath))) {
        sourcePathsWithLocaleToken.add(PathUtilRt.getParentPath(srcRootPath) + "/" + FlexCommonUtils.LOCALE_TOKEN);
      }
      else {
        sourcePathsWithoutLocaleToken.add(srcRootPath);
      }
    }

    if (includeTestRoots()) {
      for (JpsModuleSourceRoot srcRoot : myModule.getSourceRoots(JavaSourceRootType.TEST_SOURCE)) {
        final File srcRootFile = srcRoot.getFile();
        if (locales.contains(srcRootFile.getName())) {
          sourcePathsWithLocaleToken.add(srcRootFile.getParentFile().getPath() + "/" + FlexCommonUtils.LOCALE_TOKEN);
        }
        else {
          sourcePathsWithoutLocaleToken.add(srcRootFile.getPath());
        }
      }
    }

    final StringBuilder sourcePathBuilder = new StringBuilder();

    if (myCSS) {
      final String cssFolderPath = PathUtilRt.getParentPath(myBC.getMainClass());
      if (!sourcePathsWithoutLocaleToken.contains(cssFolderPath)) {
        sourcePathBuilder.append(cssFolderPath);
      }
    }

    for (final String sourcePath : sourcePathsWithLocaleToken) {
      if (sourcePathBuilder.length() > 0) {
        sourcePathBuilder.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
      }
      sourcePathBuilder.append(sourcePath);
    }

    for (final String sourcePath : sourcePathsWithoutLocaleToken) {
      if (sourcePathBuilder.length() > 0) {
        sourcePathBuilder.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
      }
      sourcePathBuilder.append(sourcePath);
    }

    addOption(rootElement, CompilerOptionInfo.SOURCE_PATH_INFO, sourcePathBuilder.toString());
  }

  private boolean includeTestRoots() {
    if (myFlexUnit) return true;
    if (myCSS) return false;
    if (myBC.getOutputType() != OutputType.Application) return false;

    final String path = FlexCommonUtils.getPathToMainClassFile(myBC.getMainClass(), myModule);
    return isInTestSourceRoot(myModule, path);
  }

  private static boolean isInTestSourceRoot(final JpsModule module, final String path) {
    for (JpsModuleSourceRoot testSrcRoot : module.getSourceRoots(JavaSourceRootType.TEST_SOURCE)) {
      final String testSrcRootPath = JpsPathUtil.urlToPath(testSrcRoot.getUrl());
      if (path.startsWith(testSrcRootPath + "/")) return true;
    }
    return false;
  }

  private void addOtherOptions(final Element rootElement) {
    final Map<String, String> options = new THashMap<String, String>(myProjectLevelCompilerOptions.getAllOptions());
    options.putAll(myModuleLevelCompilerOptions.getAllOptions());
    options.putAll(myBC.getCompilerOptions().getAllOptions());

    final String addOptions = myProjectLevelCompilerOptions.getAdditionalOptions() + " " +
                              myModuleLevelCompilerOptions.getAdditionalOptions() + " " +
                              myBC.getCompilerOptions().getAdditionalOptions();
    final List<String> contextRootInAddOptions = FlexCommonUtils.getOptionValues(addOptions, "context-root", "compiler.context-root");

    if (options.get("compiler.context-root") == null && contextRootInAddOptions.isEmpty()) {
      final List<String> servicesInAddOptions = FlexCommonUtils.getOptionValues(addOptions, "services", "compiler.services");
      if (options.get("compiler.services") != null || !servicesInAddOptions.isEmpty()) {
        options.put("compiler.context-root", "");
      }
    }

    for (final Map.Entry<String, String> entry : options.entrySet()) {
      addOption(rootElement, CompilerOptionInfo.getOptionInfo(entry.getKey()), entry.getValue());
    }

    final String namespacesRaw = options.get("compiler.namespaces.namespace");
    if (namespacesRaw != null && myBC.getOutputType() == OutputType.Library) {
      final String namespaces = FlexCommonUtils.replacePathMacros(namespacesRaw, myModule, myFlexmojos ? "" : mySdk.getHomePath());
      final StringBuilder buf = new StringBuilder();
      for (final String listEntry : StringUtil.split(namespaces, CompilerOptionInfo.LIST_ENTRIES_SEPARATOR)) {
        final int tabIndex = listEntry.indexOf(CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR);
        assert tabIndex != -1 : namespaces;
        final String namespace = listEntry.substring(0, tabIndex);
        if (buf.length() > 0) buf.append(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR);
        buf.append(namespace);
      }

      if (buf.length() > 0) {
        addOption(rootElement, CompilerOptionInfo.INCLUDE_NAMESPACES_INFO, buf.toString());
      }
    }
  }

  private void addInputOutputPaths(final Element rootElement) throws IOException {
    if (myBC.getOutputType() == OutputType.Library) {
      addFilesIncludedInSwc(rootElement);

      if (!myFlexmojos) {
        addLibClasses(rootElement);
      }
    }
    else {
      final InfoFromConfigFile info = InfoFromConfigFile.getInfoFromConfigFile(myBC.getCompilerOptions().getAdditionalConfigFilePath());

      final String pathToMainClassFile = myCSS ? myBC.getMainClass()
                                               : myFlexUnit
                                                 ? FlexCommonUtils.getPathToFlexUnitTempDirectory(myModule.getProject().getName())
                                                   + "/" + myBC.getMainClass()
                                                   + FlexCommonUtils.getFlexUnitLauncherExtension(myBC.getNature())
                                                 : FlexCommonUtils.getPathToMainClassFile(myBC.getMainClass(), myModule);

      if (pathToMainClassFile.isEmpty() && info.getMainClass(myModule) == null && !Utils.IS_TEST_MODE) {
        throw new IOException(FlexCommonBundle.message("bc.incorrect.main.class", myBC.getMainClass(), myBC.getName(), myModule.getName()));
      }

      if (!pathToMainClassFile.isEmpty()) {
        addOption(rootElement, CompilerOptionInfo.MAIN_CLASS_INFO, FileUtil.toSystemIndependentName(pathToMainClassFile));
      }
    }

    addOption(rootElement, CompilerOptionInfo.OUTPUT_PATH_INFO, myBC.getActualOutputFilePath());
  }

  private void addFilesIncludedInSwc(final Element rootElement) {
    final JpsCompilerExcludes excludes =
      JpsJavaExtensionService.getInstance().getOrCreateCompilerConfiguration(myModule.getProject()).getCompilerExcludes();

    final Map<String, String> filePathToPathInSwc = new THashMap<String, String>();

    for (String path : myBC.getCompilerOptions().getFilesToIncludeInSWC()) {
      final File fileOrDir = new File(path);
      if (excludes.isExcluded(fileOrDir)) continue;

      if (fileOrDir.isDirectory()) {
        final String baseRelativePath = StringUtil.notNullize(getPathRelativeToSourceRoot(myModule, fileOrDir.getPath()),
                                                              fileOrDir.getName());
        FileUtil.processFilesRecursively(fileOrDir, new Processor<File>() {
          public boolean process(final File file) {
            if (!file.isDirectory() &&
                !FlexCommonUtils.isSourceFile(file.getName()) &&
                !excludes.isExcluded(file)) {
              final String relativePath = FileUtil.getRelativePath(fileOrDir, file);
              final String pathInSwc = baseRelativePath.isEmpty() ? relativePath : baseRelativePath + "/" + relativePath;
              filePathToPathInSwc.put(file.getPath(), pathInSwc);
            }
            return true;
          }
        });
      }
      else if (fileOrDir.isFile()) {
        final String pathInSwc = StringUtil.notNullize(getPathRelativeToSourceRoot(myModule, fileOrDir.getPath()), fileOrDir.getName());
        filePathToPathInSwc.put(fileOrDir.getPath(), pathInSwc);
      }
    }

    for (Map.Entry<String, String> entry : filePathToPathInSwc.entrySet()) {
      final String value = entry.getValue() + CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR + entry.getKey();
      addOption(rootElement, CompilerOptionInfo.INCLUDE_FILE_INFO, value);
    }
  }

  @Nullable
  private static String getPathRelativeToSourceRoot(final JpsModule module, final String path) {
    for (JpsModuleSourceRoot srcRoot : module.getSourceRoots()) {
      final String srcRootPath = JpsPathUtil.urlToPath(srcRoot.getUrl());
      if (path.equals(srcRootPath)) return "";
      if (path.startsWith(srcRootPath + "/")) return path.substring(srcRootPath.length() + 1);
    }
    return null;
  }

  private void addOption(final Element rootElement, final CompilerOptionInfo info, final String rawValue) {
    if (!info.isApplicable(mySdk.getVersionString(), myBC.getNature())) {
      return;
    }

    final String value = FlexCommonUtils.replacePathMacros(rawValue, myModule, myFlexmojos ? "" : mySdk.getHomePath());

    final String pathInFlexConfig = info.ID.startsWith("compiler.debug") ? "compiler.debug" : info.ID;
    final List<String> elementNames = StringUtil.split(pathInFlexConfig, ".");
    Element parentElement = rootElement;

    for (int i1 = 0; i1 < elementNames.size() - 1; i1++) {
      parentElement = getOrCreateElement(parentElement, elementNames.get(i1));
    }

    final String elementName = elementNames.get(elementNames.size() - 1);

    switch (info.TYPE) {
      case Boolean:
      case String:
      case Int:
      case File:
        final Element simpleElement = new Element(elementName, parentElement.getNamespace());
        simpleElement.setText(value);
        parentElement.addContent(simpleElement);
        break;
      case List:
        if (info.LIST_ELEMENTS.length == 1) {
          final Element listHolderElement = getOrCreateElement(parentElement, elementName);
          for (final String listElementValue : StringUtil.split(value, CompilerOptionInfo.LIST_ENTRIES_SEPARATOR)) {
            final Element child = new Element(info.LIST_ELEMENTS[0].NAME, listHolderElement.getNamespace());
            child.setText(listElementValue);
            listHolderElement.addContent(child);
          }
        }
        else {
          for (final String listEntry : StringUtil.split(value, String.valueOf(CompilerOptionInfo.LIST_ENTRIES_SEPARATOR))) {
            final Element repeatableListHolderElement = new Element(elementName, parentElement.getNamespace());

            final List<String> values = StringUtil.split(listEntry, CompilerOptionInfo.LIST_ENTRY_PARTS_SEPARATOR, true, false);
            assert info.LIST_ELEMENTS.length == values.size() : info.ID + "=" + value;

            for (int i = 0; i < info.LIST_ELEMENTS.length; i++) {
              final Element child = new Element(info.LIST_ELEMENTS[i].NAME, repeatableListHolderElement.getNamespace());
              child.setText(values.get(i));
              repeatableListHolderElement.addContent(child);
            }

            parentElement.addContent(repeatableListHolderElement);
          }
        }
        break;
      default:
        assert false : info.DISPLAY_NAME;
    }
  }

  private static Element getOrCreateElement(final Element parentElement, final String elementName) {
    Element child = parentElement.getChild(elementName, parentElement.getNamespace());
    if (child == null) {
      child = new Element(elementName, parentElement.getNamespace());
      parentElement.addContent(child);
    }
    return child;
  }

  private Pair<String, ValueSource> getValueAndSource(final CompilerOptionInfo info) {
    assert !info.isGroup() : info.DISPLAY_NAME;

    final String bcLevelValue = myBC.getCompilerOptions().getOption(info.ID);
    if (bcLevelValue != null) return Pair.create(bcLevelValue, ValueSource.BC);

    final String moduleLevelValue = myModuleLevelCompilerOptions.getOption(info.ID);
    if (moduleLevelValue != null) return Pair.create(moduleLevelValue, ValueSource.ModuleDefault);

    final String projectLevelValue = myProjectLevelCompilerOptions.getOption(info.ID);
    if (projectLevelValue != null) return Pair.create(projectLevelValue, ValueSource.ProjectDefault);

    return Pair.create(info.getDefaultValue(mySdk.getVersionString(), myBC.getNature(), myBC.getDependencies().getComponentSet()),
                       ValueSource.GlobalDefault);
  }

  private static File getOrCreateConfigFile(final String fileName, final String text) throws IOException {
    final File configFile = new File(FlexCommonUtils.getTempFlexConfigsDirPath() + "/" + fileName);
    final byte[] textBytes = text.getBytes();

    /*
    try {
      if (configFile.isFile() && Arrays.equals(textBytes, FileUtil.loadFileBytes(configFile))) {
        return configFile;
      }
    }
    catch (IOException ignore) {
    }
    */

    if (!FileUtil.createParentDirs(configFile)) {
      throw new IOException("Failed to create folder " + configFile.getParent());
    }

    final FileOutputStream outputStream = new FileOutputStream(configFile);
    try {
      outputStream.write(textBytes);
    }
    finally {
      outputStream.close();
    }

    return configFile;
  }

  private static String getConfigFileName(final JpsFlexBuildConfiguration bc, final @Nullable String postfix) {
    final String prefix = "idea"; // PlatformUtils.getPlatformPrefix().toLowerCase()
    final String hash1 =
      Integer.toHexString((SystemProperties.getUserName() + bc.getModule().getProject().getName()).hashCode()).toUpperCase();
    final String hash2 = Integer.toHexString((bc.getModule().getName() + StringUtil.notNullize(bc.getName())).hashCode()).toUpperCase();
    return prefix + "-" + hash1 + "-" + hash2 + (postfix == null ? ".xml" : ("-" + StringUtil.replaceChar(postfix, ' ', '-') + ".xml"));
  }

  private static String getLinkReportFilePath(final JpsFlexBuildConfiguration bc) {
    final String fileName = getConfigFileName(bc, "link-report");
    return FlexCommonUtils.getTempFlexConfigsDirPath() + "/" + fileName;
  }

  private void addLibClasses(final Element rootElement) throws IOException {
    final JpsCompilerExcludes excludes =
      JpsJavaExtensionService.getInstance().getOrCreateCompilerConfiguration(myModule.getProject()).getCompilerExcludes();
    final Ref<Boolean> noClasses = new Ref<Boolean>(true);

    for (JpsTypedModuleSourceRoot srcRoot : myModule.getSourceRoots(JavaSourceRootType.SOURCE)) {
      final File srcFolder = JpsPathUtil.urlToFile(srcRoot.getUrl());
      if (srcFolder.isDirectory()) {
        FileUtil.processFilesRecursively(srcFolder, new Processor<File>() {
          public boolean process(final File file) {
            if (file.isDirectory()) return true;
            if (!FlexCommonUtils.isSourceFile(file.getName())) return true;
            if (excludes.isExcluded(file)) return true;

            String packageRelativePath = FileUtil.getRelativePath(srcFolder, file.getParentFile());
            assert packageRelativePath != null : srcFolder.getPath() + ": " + file.getPath();
            if (packageRelativePath.equals(".")) packageRelativePath = "";

            final String packageName = packageRelativePath.replace(File.separatorChar, '.');
            final String qName = StringUtil.getQualifiedName(packageName, FileUtil.getNameWithoutExtension(file));

            if (isSourceFileWithPublicDeclaration(file)) {
              addOption(rootElement, CompilerOptionInfo.INCLUDE_CLASSES_INFO, qName);
              noClasses.set(false);
            }

            return true;
          }
        });
      }
    }

    if (noClasses.get() && !Utils.IS_TEST_MODE) {
      throw new IOException(FlexCommonBundle.message("nothing.to.compile.in.library", myModule.getName(), myBC.getName()));
    }
  }

  private static boolean isSourceFileWithPublicDeclaration(final File file) {
    final String fileNameLowercased = file.getName().toLowerCase();
    if (fileNameLowercased.endsWith(".mxml") || fileNameLowercased.endsWith(".fxg")) {
      return true;
    }
    else if (fileNameLowercased.endsWith(".as")) {
      try {
        final String content = FileUtil.loadFile(file);
        // todo correct implementation requires lexer
        return content.contains("package");
      }
      catch (IOException e) {
        return true;
      }
    }

    return false;
  }
}
