package com.seewo.psd.bootx.loader.tools;

import org.springframework.boot.loader.tools.*;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class MyLayoutFactory implements LayoutFactory {
    private static final String NESTED_LOADER_JAR = "META-INF/loader/spring-boot-loader.jar";
    private static final String NESTED_LOADER_JAR_BOOTX = "META-INF/loader/bootx-loader.jar";

    @Override
    public Layout getLayout(File file) {
        if (file == null) {
            throw new IllegalArgumentException("File must not be null");
        }
        String lowerCaseFileName = file.getName().toLowerCase(Locale.ENGLISH);
        if (lowerCaseFileName.endsWith(".jar")) {
            return new Jar();
        }
        return Layouts.forFile(file);
    }


    public static class Jar extends org.springframework.boot.loader.tools.Layouts.Jar implements CustomLoaderLayout {
        @Override
        public void writeLoadedClasses(LoaderClassesWriter writer) throws IOException {
            writer.writeLoaderClasses(NESTED_LOADER_JAR);
            writer.writeLoaderClasses(NESTED_LOADER_JAR_BOOTX);
        }

        @Override
        public String getLauncherClassName() {
            System.out.println(">>>>>> getLauncherClassName()");
            return "com.seewo.psd.bootx.loader.JarLauncher";
        }
    }
}
