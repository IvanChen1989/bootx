package com.seewo.psd.bootx.loader;
/*
 * Copyright 2012-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


import org.springframework.boot.loader.LaunchedURLClassLoader;
import org.springframework.boot.loader.Launcher;
import org.springframework.boot.loader.archive.Archive;
import org.springframework.boot.loader.jar.Handler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * {@link ClassLoader} used by the {@link Launcher}.
 *
 * @author Phillip Webb
 * @author Dave Syer
 * @author Andy Wilkinson
 * @since 1.0.0
 */
public class JarIndexLaunchedURLClassLoader extends LaunchedURLClassLoader {

    private final static String[] excludingJar = {"java", "org.w3c.dom", "com.sun", "sun"};

    private static Map<String, List<JarFileResourceLoader>> package2LoaderMap = new ConcurrentHashMap<>();
    private static Map<String, List<JarFileResourceLoader>> res2LoaderMap = new ConcurrentHashMap<>();

    private static File JAR_INDEX_FILE = new File("INDEX.LIST");
    private static final int BUFFER_SIZE = 4096;

    /**
     * Create a new {@link org.springframework.boot.loader.LaunchedURLClassLoader} instance.
     *
     * @param urls   the URLs from which to load classes and resources
     * @param parent the parent class loader for delegation
     */
    public JarIndexLaunchedURLClassLoader(URL[] urls, ClassLoader parent) {
        this(false, urls, parent);
    }

    /**
     * Create a new {@link org.springframework.boot.loader.LaunchedURLClassLoader} instance.
     *
     * @param exploded if the underlying archive is exploded
     * @param urls     the URLs from which to load classes and resources
     * @param parent   the parent class loader for delegation
     */
    public JarIndexLaunchedURLClassLoader(boolean exploded, URL[] urls, ClassLoader parent) {
        this(exploded, null, urls, parent);
    }

    /**
     * Create a new {@link org.springframework.boot.loader.LaunchedURLClassLoader} instance.
     *
     * @param exploded    if the underlying archive is exploded
     * @param rootArchive the root archive or {@code null}
     * @param urls        the URLs from which to load classes and resources
     * @param parent      the parent class loader for delegation
     * @since 2.3.1
     */
    public JarIndexLaunchedURLClassLoader(boolean exploded, Archive rootArchive, URL[] urls, ClassLoader parent) {
        super(exploded, rootArchive, urls, parent);
        System.out.println(">>>in JarIndexLaunchedURLClassLoader");
        // 初始化 jar index信息
        initJarIndex(urls);
    }


    private void initJarIndex(URL[] urls) {
        Map<String, URL> urlMap = extracted(urls);
        Map<URL, JarFileResourceLoader> loaderMap = new HashMap<>();

        for (URL url : urls) {
            try {
                URLConnection urlConnection = url.openConnection();
                if (urlConnection instanceof JarURLConnection) {
                    loaderMap.put(url, new JarFileResourceLoader(url));
                }
            } catch (IOException e) {
                System.out.println("url open connection error " + url);
            }
        }

        Map<String, Set<String>> prefixMap = null; // jarname to package
        try {
            prefixMap = IndexParser.indexListParser(JAR_INDEX_FILE);
        } catch (IOException e) {
            System.out.println("解析INDEX.LIST文件出错， 需要排除这个错误");
        }
        if (prefixMap == null || prefixMap.size() == 0) return;

        //转换成package --> JarFileResourceLoader 所有
        prefixMap.forEach((jarName, packageNameSet) -> {
            URL url = urlMap.get(jarName);
            if (url == null) {
                //System.out.println("get jar url null: url is : " + url);
                return;
            }
            for (String pkgName : packageNameSet) {
                List<JarFileResourceLoader> jarFileResourceLoaders = package2LoaderMap.computeIfAbsent(pkgName, key -> new ArrayList<>());
                if (loaderMap.get(url) != null) {
                    jarFileResourceLoaders.add(loaderMap.get(url));
                }
            }
        });
        System.out.println("package2LoaderMap size is : " + package2LoaderMap.size());
    }

    private Map<String, URL> extracted(URL[] urls) {
        Map<String, URL> urlMap = new HashMap<>();
        for (URL url : urls) {
            String urlStr = url.toString();
            int idx = urlStr.indexOf(".jar!");
            if (idx < 0) continue;
            if (urlStr.endsWith("!/")) {
                urlStr = urlStr.substring(idx + ".jar!".length(), urlStr.length() - 2);
            } else {
                urlStr = urlStr.substring(idx + ".jar!".length());
            }
            urlMap.put(urlStr, url);
        }
        return urlMap;
    }

    @Override
    public URL findResource(String name) {
        List<JarFileResourceLoader> loaders = res2LoaderMap.get(name);
        if (loaders != null && !loaders.isEmpty()) {
            for (JarFileResourceLoader loader : loaders) {
                URL ret = loader.getResource(name);
                if (ret == null) continue;
                return ret;
            }
        }
        return super.findResource(name);
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        List<JarFileResourceLoader> loaders = res2LoaderMap.get(name);
        if (loaders != null && !loaders.isEmpty()) {
            List<URL> targetUrl = new ArrayList<>();
            for (JarFileResourceLoader loader : loaders) {
                try {
                    URL ret = loader.getResource(name);
                    if (ret == null) continue;
                    targetUrl.add(ret);
                } catch (Exception e) {
                }
            }
            if (!targetUrl.isEmpty()) {
                return Collections.enumeration(targetUrl);
            }
        }
        return super.findResources(name);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // load loader classes directly
        Handler.setUseFastConnectionExceptions(true);
        try {
            synchronized (getClassLoadingLock(name)) {

                Class<?> loadedClass = findLoadedClass(name);
                if (loadedClass != null) return loadedClass;
                //加载器类， 直接加载
                if (name.startsWith("org.springframework.boot.loader.") || name.startsWith("com.seewo.psd.bootx.loader.")) {
                    try {
                        Class<?> result = loadClassInLaunchedClassLoader(name);
                        if (resolve) {
                            resolveClass(result);
                        }
                        return result;
                    } catch (ClassNotFoundException ex) {
                    }
                }

                // skip java.*, org.w3c.dom.* com.sun.*
                if (!Arrays.stream(excludingJar).anyMatch(item -> name.startsWith(item))) {
                     //System.out.println(">>>>>loading " + name);
                    int lastDot = name.lastIndexOf('.');
                    if (lastDot >= 0) {
                        String packageName = name.substring(0, lastDot);
                        String packageEntryName = packageName.replace('.', '/');
                        String path = name.replace('.', '/').concat(".class");


                        List<JarFileResourceLoader> loaders = package2LoaderMap.get(packageEntryName);
                        if (loaders != null) {
                            for (JarFileResourceLoader loader : loaders) {
                                ClassSpec classSpec = null;
                                try {
                                    classSpec = loader.getClassSpec(path);
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }

                                if (classSpec == null) {
                                    //没找到， 可能再其他jar包中
                                    //System.out.println(">>>>> resource is null: " + packageName + "\t" + path);
                                    continue;
                                }
                                Class<?> definedClass = defineClass(name, classSpec.getBytes(), 0, classSpec.getBytes().length, classSpec.getCodeSource());
                                //System.out.println(">>>>> define class done: " + "\t" + packageName + "\t" + path + "\t" + definedClass);
                                definePackageIfNecessary(name);
                                return definedClass;
                            }
                        }
                    }
                }

                //其他的类， 让父类加载器来加载
                return super.loadClass(name, resolve);
            }
        } finally {
            Handler.setUseFastConnectionExceptions(false);
        }
    }

    private Class<?> loadClassInLaunchedClassLoader(String name) throws ClassNotFoundException {
        String internalName = name.replace('.', '/') + ".class";
        InputStream inputStream = getParent().getResourceAsStream(internalName);
        if (inputStream == null) {
            throw new ClassNotFoundException(name);
        }
        try {
            try {
                ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead = -1;
                while ((bytesRead = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, bytesRead);
                }
                inputStream.close();
                byte[] bytes = outputStream.toByteArray();
                Class<?> definedClass = defineClass(name, bytes, 0, bytes.length);
                definePackageIfNecessary(name);
                return definedClass;
            } finally {
                inputStream.close();
            }
        } catch (IOException ex) {
            throw new ClassNotFoundException("Cannot load resource for class [" + name + "]", ex);
        }
    }

    /**
     * Define a package before a {@code findClass} call is made. This is necessary to
     * ensure that the appropriate manifest for nested JARs is associated with the
     * package.
     *
     * @param className the class name being found
     */
    private void definePackageIfNecessary(String className) {
        int lastDot = className.lastIndexOf('.');
        if (lastDot >= 0) {
            String packageName = className.substring(0, lastDot);
            if (getPackage(packageName) == null) {
                try {
                    definePackage(className, packageName);
                } catch (IllegalArgumentException ex) {
                    // Tolerate race condition due to being parallel capable
                    if (getPackage(packageName) == null) {
                        // This should never happen as the IllegalArgumentException
                        // indicates that the package has already been defined and,
                        // therefore, getPackage(name) should not have returned null.
                        throw new AssertionError(
                                "Package " + packageName + " has already been defined but it could not be found");
                    }
                }
            }
        }
    }

    private void definePackage(String className, String packageName) {
        try {
            AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
                String packageEntryName = packageName.replace('.', '/') + "/";
                String classEntryName = className.replace('.', '/') + ".class";
                for (URL url : getURLs()) {
                    try {
                        URLConnection connection = url.openConnection();
                        if (connection instanceof JarURLConnection) {
                            JarFile jarFile = ((JarURLConnection) connection).getJarFile();
                            if (jarFile.getEntry(classEntryName) != null && jarFile.getEntry(packageEntryName) != null
                                    && jarFile.getManifest() != null) {
                                definePackage(packageName, jarFile.getManifest(), url);
                                return null;
                            }
                        }
                    } catch (IOException ex) {
                        // Ignore
                    }
                }
                return null;
            }, AccessController.getContext());
        } catch (java.security.PrivilegedActionException ex) {
            // Ignore
        }
    }
}
