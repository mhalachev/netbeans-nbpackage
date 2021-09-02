/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.netbeans.nbpackage.appimage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.netbeans.nbpackage.AbstractPackagerTask;
import org.apache.netbeans.nbpackage.ExecutionContext;
import org.apache.netbeans.nbpackage.NBPackage;

class AppImageTask extends AbstractPackagerTask {

    private String execName;

    AppImageTask(ExecutionContext context) {
        super(context);
    }

    @Override
    public void validateCreatePackage() throws Exception {
//        var tool = context().getValue(AppImagePackager.APPIMAGE_TOOL)
//                .orElseThrow(() -> new IllegalStateException(
//                AppImagePackager.MESSAGES.getString("message.noappimagetool")));
//        if (!Files.isExecutable(tool)) {
//            throw new IllegalStateException(
//                    AppImagePackager.MESSAGES.getString("message.noappimagetool"));
//        }
    }

    @Override
    public Path createImage(Path input) throws Exception {
        var image = super.createImage(input);
        Path usrLib = image.resolve("usr").resolve("lib");
        String execName = findLauncher(
                usrLib.resolve("APPDIR").resolve("bin"))
                .getFileName().toString();

        Path appDir = usrLib.resolve(execName);
        Files.move(usrLib.resolve("APPDIR"), appDir);

        Path usrBin = image.resolve("usr").resolve("bin");
        Files.createDirectories(usrBin);
        Files.createSymbolicLink(usrBin.resolve(execName),
                usrBin.relativize(appDir.resolve("bin").resolve(execName)));

        setupIcons(image, execName);
        setupDesktopFile(image, execName);
        setupAppRunScript(image, execName);
        
        return image;
        
    }

    @Override
    public Path createPackage(Path image, List<Path> buildFiles) throws Exception {
        return image;
    }

    @Override
    protected String imageName(Path input) throws Exception {
        var version = sanitize(context().getValue(NBPackage.PACKAGE_VERSION).orElse(""));
        return sanitize(context().getValue(NBPackage.PACKAGE_NAME).orElseThrow())
                + (version.isBlank() ? "-AppDir" : "-" + version + "-AppDir");
    }

    @Override
    protected Path applicationDirectory(Path image) throws Exception {
        // change name to launcher name later
        return image.resolve("usr/lib/APPDIR");
    }

    @Override
    protected Path runtimeDirectory(Path image, Path application) throws Exception {
        return image.resolve("usr/lib/jdk");
    }

    private String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9-_\\.]", "_");
    }

    private Path findLauncher(Path binDir) throws IOException {
        try ( var files = Files.list(binDir)) {
            return files.filter(f -> !f.getFileName().toString().endsWith(".exe"))
                    .findFirst().orElseThrow(IOException::new);
        }
    }

    private void setupIcons(Path image, String execName) throws IOException {
        Path iconDir = image.resolve("usr")
                .resolve("share")
                .resolve("icons")
                .resolve("hicolor")
                .resolve("48x48")
                .resolve("apps");
        Files.createDirectories(iconDir);
        Path iconFile = iconDir.resolve(execName + ".png");
        Path icon = context().getValue(AppImagePackager.APPIMAGE_ICON).orElse(null);
        if (icon != null) {
            Files.copy(icon, iconFile);
        } else {
            Files.copy(getClass().getResourceAsStream(
                    "/org/apache/netbeans/nbpackage/apache-netbeans-48x48.png"),
                    iconFile
            );
        }
        Files.createSymbolicLink(image.resolve(".AppDir"), image.relativize(iconFile));
        Files.createSymbolicLink(image.resolve(execName + ".png"), image.relativize(iconFile));
    }

    private void setupDesktopFile(Path image, String execName) throws IOException {
        String template;
        try ( var reader = new BufferedReader(
                new InputStreamReader(
                        getClass().getResourceAsStream("AppImage.desktop.template")))) {
            template = reader.lines().collect(Collectors.joining("\n", "", "\n"));
        }
        String desktop = context().replaceTokens(template,
                key -> "EXEC".equals(key) ? execName : context().tokenReplacementFor(key));
        Path desktopDir = image.resolve("usr")
                .resolve("share")
                .resolve("applications");
        Files.createDirectories(desktopDir);
        Path desktopFile = desktopDir.resolve(execName + ".desktop");
        Files.writeString(desktopFile, desktop, StandardOpenOption.CREATE_NEW);
        Files.createSymbolicLink(image.resolve(execName + ".desktop"),
                image.relativize(desktopFile));
    }

    private void setupAppRunScript(Path image, String execName) throws IOException {
        String template;
        try ( var reader = new BufferedReader(
                new InputStreamReader(
                        getClass().getResourceAsStream("AppImage.launcher.template")))) {
            template = reader.lines().collect(Collectors.joining("\n", "", "\n"));
        }
        String appRun = context().replaceTokens(template, key -> {
            if ("EXEC".equals(key)) {
                return execName;
            } else {
                // assume part of script and put back
                return "${" + key + "}";
            }
        });
        Path appRunPath = image.resolve("AppRun");
        Files.writeString(appRunPath, appRun, StandardOpenOption.CREATE_NEW);
        Files.setPosixFilePermissions(appRunPath, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

}
