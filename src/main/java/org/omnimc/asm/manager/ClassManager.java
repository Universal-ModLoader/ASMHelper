/*
 * MIT License
 *
 * Copyright (c) 2024 OmniMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.omnimc.asm.manager;

import org.omnimc.asm.changes.IClassChange;
import org.omnimc.asm.changes.IResourceChange;
import org.omnimc.asm.common.ByteUtil;
import org.omnimc.asm.common.exception.ExceptionHandler;
import org.omnimc.asm.file.ClassFile;
import org.omnimc.asm.file.IOutputFile;
import org.omnimc.asm.file.ResourceFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * <h6>{@linkplain ClassManager} manages a collection of classes and resources from a JAR file.
 * <p>
 * It provides methods to read a JAR file, apply changes to classes and resources, and generate an
 * {@linkplain IOutputFile} containing modified classes and resources.
 *
 * <p><b>Usage:</b></p>
 * <pre>{@code
 *    public static void main(String[] args) {
 *        // Creating an instance of ClassManager.
 *        ClassManager classManager = new ClassManager();
 *
 *        classManager.readJarFile(new File("Random.jar")); // JAR you want to read.
 *
 *        // Applying changes to classes.
 *        classManager.applyChanges((IClassChange) (name, classBytes) -> { // This is the new way of applying changes.
 *            // You have to set up your own ClassReader and ClassWriter.
 *            // Then in this example we are accepting a class that extends ClassVisitor.
 *            ClassReader cr = new ClassReader(classBytes);
 *            ClassWriter writer = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES);
 *            cr.accept(new TestVisitor(Opcodes.ASM9, writer), ClassReader.EXPAND_FRAMES);
 *            // You have to change the name separately as of right now.
 *
 *            return new ClassFile("Modified" + name, writer.toByteArray()); // Returns a ClassFile.
 *        });
 *
 *        // Applying changes to resources
 *        classManager.applyChanges((IResourceChange) (name, data) -> { // This is the new way of applying changes.
 *            name = "Monkey"; // This will overwrite every entry and cause issues don't use this as code.
 *            return new ResourceFile(name, data); // Returns a ResourceFile.
 *        });
 *
 *        IOutputFile outputFile = classManager.outputFile(); // This returns the Output data.
 *
 *        byte[] outputBytes = outputFile.getFileInBytes(Deflater.DEFLATED); // This is how you set the Compression Level.
 *    }
 * }</pre>
 *
 * @author <b><a href="https://github.com/CadenCCC">Caden</a></b>
 * @since 1.0.0
 */
public class ClassManager implements IClassManager {

    /**
     * Represents the name of the JAR file inputted.
     */
    private String fileName;
    /**
     * Represents the classes of the JAR file inputted.
     */
    private final HashMap<String, byte[]> classes = new HashMap<>();
    /**
     * Represents the resources of the JAR file inputted.
     */
    private final HashMap<String, byte[]> resources = new HashMap<>();

    /**
     * <h6>Reads a JAR file and populates the {@linkplain #classes} and {@linkplain #resources} collections.
     * <p>If the JAR file contains classes (.class files), they are parsed using the ASM library
     * and stored as byte arrays in the {@linkplain #classes} map. Other resources are stored in the
     * {@linkplain #resources} map. and stored as byte arrays in the {@linkplain #classes} map. Other resources are
     * stored in the {@linkplain #resources} map.
     *
     * @param fileInput The input File object representing the JAR file to be read. Must not be null.
     * @throws NullPointerException If the provided file is null.
     * @throws RuntimeException     If the provided file is not a JAR file or if an I/O error occurs.
     * @throws RuntimeException     If the provided file is not a JAR file or if an I/O error occurs.
     */
    @Override
    public void readJarFile(File fileInput) {
        Objects.requireNonNull(fileInput, "You cannot have a NULL file as an input.");

        if (!fileInput.getName().endsWith(".jar")) {
            ExceptionHandler.handleException(new IllegalArgumentException("Input file HAS to be a JAR file!"));
            return;
        }

        this.fileName = fileInput.getName();

        try (JarFile jar = new JarFile(fileInput)) {
            List<JarEntry> classEntries = Collections.list(jar.entries());

            for (JarEntry classEntry : classEntries) {
                String name = classEntry.getName();
                if (name.endsWith("/")) { // Can't read folders
                    continue;
                }

                try {
                    /* Creating streams */
                    // We need to make sure we have all the streams available, so we can close them later on. (saving performance)
                    InputStream stream = jar.getInputStream(classEntry);
                    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

                    byte[] value = ByteUtil.toByteArray(stream, outputStream);

                    if (name.contains(".class")) {
                        /* Adding classes */
                        if (classes.containsKey(name)) {
                            return;
                        }

                        classes.putIfAbsent(name, value);
                    } else {
                        /* Adding non-class entries */
                        if (resources.containsKey(name)) {
                            return;
                        }

                        resources.putIfAbsent(name, value);
                    }

                    // Closing streams to free resources.
                    stream.close();
                    outputStream.close();
                } catch (IOException e) {
                    ExceptionHandler.handleException("Failed to read bytes for '" + name + "', in '" + fileName + "'.", e);
                }
            }
        } catch (IOException e) {
            ExceptionHandler.handleException("Failure to read '" + fileName + "', Could be that it is corrupted or not a real JAR file.", e);
        }
    }

    /**
     * <h6>Applies changes to classes based on a provided array of {@linkplain IClassChange}.
     * <p>This method iterates through the {@linkplain #classes} map, applying changes using the array of
     * {@linkplain IClassChange} implementations provided.
     * <p>This method iterates through the {@linkplain #classes} map, applying changes using the array of
     * {@linkplain IClassChange} implementations provided.
     *
     * @param classChanges Array of {@linkplain IClassChange} implementations for modifying classes. Must not be null.
     */
    @Override
    public void applyChanges(IClassChange... classChanges) {
        if (classChanges == null || classChanges.length == 0) {
            return;
        }

        if (classes.isEmpty()) {
            return;
        }

        /* Applying class changes and replacing them */
        HashMap<String, byte[]> tempHashMap = new HashMap<>(classes);

        for (IClassChange change : classChanges) {
            HashMap<String, byte[]> updatedTempHashMap = new HashMap<>();

            tempHashMap.forEach((className, classData) -> {
                ClassFile modifiedClassFile = change.applyChange(className, classData);

                if (modifiedClassFile != null) {
                    updatedTempHashMap.put(modifiedClassFile.getKey(), modifiedClassFile.getValue());
                }
            });

            tempHashMap = updatedTempHashMap;
        }

        classes.clear();
        classes.putAll(tempHashMap);
    }

    /**
     * <h6>Applies changes to resources based on a provided array of {@linkplain IResourceChange}.
     * <p>This method iterates through the {@linkplain #resources} map, applying changes using the list of
     * {@linkplain IResourceChange} provided.
     *
     * @param resourceChanges Array of {@linkplain IResourceChange} implementations for modifying resources.
     */
    @Override
    public void applyChanges(IResourceChange... resourceChanges) {
        if (resourceChanges == null || resourceChanges.length == 0) {
            return;
        }

        if (resources.isEmpty()) {
            return;
        }

        /* Applying resource changes and replacing them */
        HashMap<String, byte[]> tempHashMap = new HashMap<>(resources);

        for (IResourceChange change : resourceChanges) {
            HashMap<String, byte[]> updatedTempHashMap = new HashMap<>();

            tempHashMap.forEach((resourceName, resourceData) -> {
                ResourceFile modifiedResourceFile = change.applyChange(resourceName, resourceData);

                if (modifiedResourceFile != null) {
                    updatedTempHashMap.put(modifiedResourceFile.getKey(), modifiedResourceFile.getValue());
                }
            });

            tempHashMap = updatedTempHashMap;
        }

        resources.clear();
        resources.putAll(tempHashMap);
    }

    /**
     * <h6>Generates an {@linkplain IOutputFile} containing modified classes and resources.
     * <p>It creates a ZIP file in memory and adds modified classes and resources to it.
     * The output file can be retrieved as a byte array.
     *
     * @return An instance of {@linkplain IOutputFile} representing the generated output file.
     */
    @Override
    public IOutputFile outputFile() {
        return new IOutputFile() {

            @Override
            public String getFileName() {
                return fileName;
            }

            @Override
            public byte[] getFileInBytes(int compression) {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();

                try (ZipOutputStream zipOutputStream = new ZipOutputStream(byteArrayOutputStream)) {
                    zipOutputStream.setLevel(compression);
                    // add classes to the zip output stream
                    for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                        String entryName = entry.getKey();
                        if (!entryName.contains(".class")) {
                            entryName = entryName + ".class";
                        }

                        byte[] entryData = entry.getValue();

                        zipOutputStream.putNextEntry(new ZipEntry(entryName));
                        zipOutputStream.write(entryData);
                        zipOutputStream.closeEntry();
                    }

                    // add resources to the zip output stream
                    for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                        if (entry.getValue() == null) {
                            continue;
                        }

                        zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
                        zipOutputStream.write(entry.getValue());
                        zipOutputStream.closeEntry();
                    }
                } catch (IOException e) {
                    ExceptionHandler.handleException("Failed compressing classes/resources. Possible I/O error??", e);
                }

                return byteArrayOutputStream.toByteArray();
            }
        };
    }

    public HashMap<String, byte[]> getClasses() {
        return new HashMap<>(classes);
    }

    public HashMap<String, byte[]> getResources() {
        return new HashMap<>(resources);
    }

    /**
     * <h6>Closes resources and clears internal collections.
     * <p>
     * It resets {@linkplain #fileName}, clears {@linkplain #classes} map, and clears {@linkplain #resources} map.
     */
    @Override
    public void close() {
        this.fileName = null;
        classes.clear();
        resources.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ClassManager that = (ClassManager) o;
        return Objects.equals(fileName, that.fileName)
               && Objects.equals(getClasses(), that.getClasses())
               && Objects.equals(getResources(), that.getResources());
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, getClasses(), getResources());
    }

    @Override
    public String toString() {
        return "ClassManager{" +
               "fileName='" + fileName + '\'' +
               ", classes=" + classes +
               ", resources=" + resources +
               '}';
    }
}