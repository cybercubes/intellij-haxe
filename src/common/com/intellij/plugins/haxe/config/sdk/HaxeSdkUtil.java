/*
 * Copyright 2000-2013 JetBrains s.r.o.
 * Copyright 2014-2014 AS3Boyan
 * Copyright 2014-2014 Elias Ku
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.plugins.haxe.config.sdk;

import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.process.CapturingProcessHandler;
import com.intellij.execution.process.ProcessOutput;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.projectRoots.SdkModificator;
import com.intellij.openapi.roots.JavadocOrderRootType;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.plugins.haxe.util.HaxeSdkUtilBase;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HaxeSdkUtil extends HaxeSdkUtilBase {
  private static final Logger LOG = Logger.getInstance("#com.intellij.plugins.haxe.config.sdk.HaxeSdkUtil");
  private static final Pattern VERSION_MATCHER = Pattern.compile("(\\d+(\\.\\d+)+)");

  @Nullable
  public static HaxeSdkData testHaxeSdk(String path) {
    final String exePath = getCompilerPathByFolderPath(path);

    if (exePath == null) {
      return null;
    }

    final GeneralCommandLine command = new GeneralCommandLine();
    command.setExePath(exePath);
    command.addParameter("-help");
    command.setWorkDirectory(path);

    try {
      final ProcessOutput output = new CapturingProcessHandler(
        command.createProcess(),
        Charset.defaultCharset(),
        command.getCommandLineString()).runProcess();

      if (output.getExitCode() != 0) {
        LOG.error("Haxe compiler exited with invalid exit code: " + output.getExitCode());
        return null;
      }

      final String outputString = output.getStderr();

      String haxeVersion = "NA";
      final Matcher matcher = VERSION_MATCHER.matcher(outputString);
      if (matcher.find()) {
        haxeVersion = matcher.group(1);
      }
      final HaxeSdkData haxeSdkData = new HaxeSdkData(path, haxeVersion);
      haxeSdkData.setHaxelibPath(getHaxelibPathByFolderPath(path));
      haxeSdkData.setNekoBinPath(suggestNekoBinPath(path));
      return haxeSdkData;
    }
    catch (ExecutionException e) {
      LOG.info("Exception while executing the process:", e);
      return null;
    }
  }

  public static void setupSdkPaths(@Nullable VirtualFile sdkRoot, SdkModificator modificator) {
    if (sdkRoot == null) {
      return;
    }
    VirtualFile stdRoot;
    final String stdPath = System.getenv("HAXE_STD_PATH");
    if (stdPath != null) {
      stdRoot = VirtualFileManager.getInstance().findFileByUrl("file://" + stdPath);
    }
    else {
      stdRoot = sdkRoot.findChild("std");
    }
    if (stdRoot != null) {
      modificator.addRoot(stdRoot, OrderRootType.SOURCES);
      modificator.addRoot(stdRoot, OrderRootType.CLASSES);
    }
    VirtualFile docRoot;
    final String docPath = System.getenv("HAXE_DOC_PATH");
    if (docPath != null) {
      docRoot = VirtualFileManager.getInstance().findFileByUrl(docPath);
    }
    else {
      docRoot = sdkRoot.findChild("doc");
    }
    if (docRoot != null) {
      modificator.addRoot(docRoot, JavadocOrderRootType.getInstance());
    }
  }

  @Nullable
  private static String suggestNekoBinPath(@NotNull String path) {
    final String binName = "neko";
    String result = null;

    String nekoDir = System.getenv("NEKOPATH");
    if (nekoDir == null) {
      nekoDir = System.getenv("NEKO_INSTPATH");
    }
    if(nekoDir != null) {
      File nekoFile =  new File(nekoDir, binName);
      if(nekoFile.exists()) {
        result = nekoFile.getPath();
      }
    }

    if(result == null) {
      result = locateExecutable(binName);
    }

    LOG.debug("returning neko path: " + String.valueOf(result));
    return result;
  }

  @Nullable
  public static String suggestHomePath() {
    //HAXEPATH is created by windows installer. Used by haxelib.
    String haxePath = System.getenv("HAXEPATH");
    if(haxePath != null) {
      return haxePath;
    }

    //Specifies the path to `std` directory in SDK. Used by Haxe compiler.
    String stdPath = System.getenv("HAXE_STD_PATH");
    if(stdPath != null) {
      return new File(stdPath).getParent();
    }

    //Try to locate SDK path relative to the compiler executable.
    String compilerPath = locateExecutable("haxe");
    if(compilerPath != null) {
      String presumedSdkPath = new File(compilerPath).getParent();
      if(new File(presumedSdkPath, "std/Array.hx").exists()) {
        return presumedSdkPath;
      }
    }

    return null;
  }

  /**
   * Look for specified program in directories which are listed in the PATH environment variable.
   * @return Canonical path (absolute, symlinks resolved) to `executable` if found. `null` otherwise.
   */
  @Nullable
  private static String locateExecutable(String executable) {
    executable = getExecutableName(executable);
    String pathEnv = System.getenv("PATH");
    String[] paths = pathEnv.split(SystemInfo.isWindows ? ";" : ":");
    for(String path:paths) {
      File file = new File(path, executable);
      if(file.exists()) {
        try {
          return file.getCanonicalPath();
        }
        catch (IOException e) {}
      }
    }
    return null;
  }
}
