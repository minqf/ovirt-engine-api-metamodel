/*
Copyright (c) 2015-2016 Red Hat, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.ovirt.api.metamodel.analyzer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.thoughtworks.qdox.JavaProjectBuilder;
import com.thoughtworks.qdox.model.DocletTag;
import com.thoughtworks.qdox.model.JavaAnnotatedElement;
import com.thoughtworks.qdox.model.JavaAnnotation;
import com.thoughtworks.qdox.model.JavaClass;
import com.thoughtworks.qdox.model.JavaField;
import com.thoughtworks.qdox.model.JavaMethod;
import com.thoughtworks.qdox.model.JavaModel;
import com.thoughtworks.qdox.model.JavaParameter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.ovirt.api.metamodel.annotations.Root;
import org.ovirt.api.metamodel.concepts.Annotation;
import org.ovirt.api.metamodel.concepts.AnnotationParameter;
import org.ovirt.api.metamodel.concepts.Attribute;
import org.ovirt.api.metamodel.concepts.Concept;
import org.ovirt.api.metamodel.concepts.Constraint;
import org.ovirt.api.metamodel.concepts.Document;
import org.ovirt.api.metamodel.concepts.EnumType;
import org.ovirt.api.metamodel.concepts.EnumValue;
import org.ovirt.api.metamodel.concepts.Expression;
import org.ovirt.api.metamodel.concepts.Link;
import org.ovirt.api.metamodel.concepts.ListType;
import org.ovirt.api.metamodel.concepts.Locator;
import org.ovirt.api.metamodel.concepts.Method;
import org.ovirt.api.metamodel.concepts.Model;
import org.ovirt.api.metamodel.concepts.Module;
import org.ovirt.api.metamodel.concepts.Name;
import org.ovirt.api.metamodel.concepts.NameParser;
import org.ovirt.api.metamodel.concepts.Parameter;
import org.ovirt.api.metamodel.concepts.Service;
import org.ovirt.api.metamodel.concepts.StructType;
import org.ovirt.api.metamodel.concepts.Type;

/**
 * This class analyzes the Java sources from a directory and populates a model with the concepts extracted from it.
 */
public class ModelAnalyzer {
    /**
     * This suffix will removed from service names.
     */
    private static final String SERVICE_SUFFIX = "Service";

    /**
     * Name of the {@code value} parameter of annotations created from Javadoc tags.
     */
    private static final Name VALUE = NameParser.parseUsingCase("Value");

    /**
     * Reference to the model that will be populated.
     */
    private Model model;

    /**
     * This list is used to remember the names of the types that haven't been defined yet, and the setters that can be
     * used to change them.
     */
    private List<TypeUsage> undefinedTypeUsages = new ArrayList<>();

    /**
     * This list is used to remember the names of the services that haven't been defined yet, and the setters that can
     * be * used to change them.
     */
    private List<ServiceUsage> undefinedServiceUsages = new ArrayList<>();

    /**
     * In order to avoid creating multiple anonymous list types for the same element type we keep this index, where
     * the keys are the names of the element types and the values are the list types that have been created.
     */
    private Map<Name, ListType> listTypes = new HashMap<>();

    /**
     * The constraints can't be completely analyzed till all the types have been resolved, so we store them in this
     * list in order to analyze them later.
     */
    private List<Constraint> undefinedConstraints = new ArrayList<>();

    /**
     * Sets the model that will be populated by this analyzer.
     */
    public void setModel(Model newModel) {
        model = newModel;
    }

    /**
     * Returns a reference to the model that is currently being populated by this analyzer.
     */
    public Model getModel() {
        return model;
    }

    /**
     * Analyzes all the model source files contained in the given directory or {@code .jar file}, extracts the concepts
     * and populates the model that has been previously set with the {@link #setModel(Model)} method.
     *
     * @param sourceFile the directory or {@code .jar} file containing the model source files
     * @throws IOException if something fails while scanning the model source files
     */
    public void analyzeSource(File sourceFile) throws IOException {
        // Create the QDox project:
        JavaProjectBuilder project = new JavaProjectBuilder();

        // If the given source file is actually a directory, then we can directly analyze it, but if it is a .jar file
        // we need to iterate the contents file by file, as QDox doesn't directly support loading .jar files:
        if (sourceFile.isDirectory()) {
            project.addSourceTree(sourceFile);
            Collection<File> documentFiles = FileUtils.listFiles(sourceFile, new String[] { "adoc" }, true);
            for (File documentFile : documentFiles) {
                try (InputStream documentIn = new FileInputStream(documentFile)) {
                    analyzeDocument(documentFile.getName(), documentIn);
                }
            }
        }
        else if (sourceFile.isFile() && sourceFile.getName().endsWith(".jar")) {
            try (ZipFile zipFile = new ZipFile(sourceFile)) {
                Enumeration<? extends ZipEntry> zipEntries = zipFile.entries();
                while (zipEntries.hasMoreElements()) {
                    ZipEntry zipEntry = zipEntries.nextElement();
                    String zipEntryName = zipEntry.getName();
                    if (zipEntryName.endsWith(".java")) {
                        try (InputStream sourceIn = zipFile.getInputStream(zipEntry)) {
                            try (Reader sourceReader = new InputStreamReader(sourceIn, Charset.forName("UTF-8"))) {
                                project.addSource(sourceReader);
                            }
                        }
                    }
                    else if (zipEntryName.endsWith(".adoc")) {
                        try (InputStream documentIn = zipFile.getInputStream(zipEntry)) {
                            analyzeDocument(zipEntryName, documentIn);
                        }
                    }
                }
            }
        }
        else {
            throw new IOException(
                "Don't know how to parse source file \"" + sourceFile.getAbsolutePath() + "\", should be a " +
                "directory or a .jar file."
            );
        }

        // Process the classes, discarding inner classes als they will be processed as part of the processing of the
        // class containing them:
        project.getClasses().stream().filter(x -> !x.isInner()).forEach(this::analyzeClass);

        // Fix the places where undefined types and services have been used, replacing them with the corresponding
        // completely defined ones:
        fixUndefinedTypeUsages();
        fixUndefinedServiceUsages();

        // Analyze constraints:
        parseConstraints();
    }

    private void analyzeClass(JavaClass javaClass) {
        if (isAnnotatedWith(javaClass, ModelAnnotations.TYPE)) {
            if (javaClass.isEnum()) {
                analyzeEnum(javaClass);
            }
            else {
                analyzeStruct(javaClass);
            }
        }
        if (isAnnotatedWith(javaClass, ModelAnnotations.SERVICE) || isAnnotatedWith(javaClass, ModelAnnotations.ROOT)) {
            analyzeService(javaClass);
        }
    }

    private void analyzeEnum(JavaClass javaClass) {
        // Create the type:
        EnumType type = new EnumType();
        analyzeSource(javaClass, type);
        analyzeModule(javaClass, type);
        analyzeName(javaClass, type);
        analyzeAnnotations(javaClass, type);
        analyzeDocumentation(javaClass, type);

        // Get the values:
        javaClass.getEnumConstants().forEach(x -> analyzeEnumValue(x, type));

        // Add the type to the model:
        model.addType(type);
    }

    private void analyzeEnumValue(JavaField javaField, EnumType type) {
        // Create the value:
        EnumValue value = new EnumValue();
        analyzeSource(javaField, value);
        analyzeName(javaField, value);
        analyzeAnnotations(javaField, type);
        analyzeDocumentation(javaField, value);

        // Add the value to the type:
        value.setDeclaringType(type);
        type.addValue(value);
    }

    private void analyzeStruct(JavaClass javaClass) {
        // Create the type:
        StructType type = new StructType();
        analyzeModule(javaClass, type);
        analyzeName(javaClass, type);
        analyzeAnnotations(javaClass, type);
        analyzeDocumentation(javaClass, type);

        // Analyze the base type:
        JavaClass javaSuperClass = null;
        if (javaClass.isInterface()) {
            List<JavaClass> javaSuperInterfaces = javaClass.getInterfaces();
            if (javaSuperInterfaces != null && javaSuperInterfaces.size() > 0) {
                javaSuperClass = javaSuperInterfaces.get(0);
            }
        }
        else {
            javaSuperClass = javaClass.getSuperJavaClass();
        }
        if (javaSuperClass != null) {
            String javaSuperClassName = javaSuperClass.getName();
            Name baseTypeName = parseJavaName(javaSuperClassName);
            assignType(baseTypeName, type::setBase);
        }

        // Analyze the members:
        javaClass.getMethods(false).forEach(x -> analyzeStructMember(x, type));

        // Add the type to the model:
        model.addType(type);
    }

    private void analyzeStructMember(JavaMethod javaMethod, StructType type) {
        if (isAnnotatedWith(javaMethod, ModelAnnotations.LINK)) {
            analyzeStructLink(javaMethod, type);
        }
        else {
            analyzeStructAttribute(javaMethod, type);
        }
    }

    private void analyzeStructLink(JavaMethod javaMethod, StructType type) {
        // Create the model:
        Link link = new Link();
        analyzeName(javaMethod, link);
        analyzeAnnotations(javaMethod, link);
        analyzeDocumentation(javaMethod, link);

        // Get the type:
        assignTypeReference(javaMethod.getReturns(), link::setType);

        // Add the member to the struct:
        link.setDeclaringType(type);
        type.addLink(link);
    }

    private void analyzeStructAttribute(JavaMethod javaMethod, StructType type) {
        // Create the model:
        Attribute attribute = new Attribute();
        analyzeName(javaMethod, attribute);
        analyzeAnnotations(javaMethod, attribute);
        analyzeDocumentation(javaMethod, attribute);

        // Get the type:
        assignTypeReference(javaMethod.getReturns(), attribute::setType);

        // Add the member to the struct:
        attribute.setDeclaringType(type);
        type.addAttribute(attribute);
    }

    private void analyzeService(JavaClass javaClass) {
        // Create the service:
        Service service = new Service();
        analyzeModule(javaClass, service);
        analyzeName(javaClass, service);
        analyzeAnnotations(javaClass, service);
        analyzeDocumentation(javaClass, service);

        // Analyze the base service:
        JavaClass javaSuperClass = null;
        if (javaClass.isInterface()) {
            List<JavaClass> javaSuperInterfaces = javaClass.getInterfaces();
            if (javaSuperInterfaces != null && javaSuperInterfaces.size() > 0) {
                javaSuperClass = javaSuperInterfaces.get(0);
            }
        }
        else {
            javaSuperClass = javaClass.getSuperJavaClass();
        }
        if (javaSuperClass != null) {
            String javaSuperClassName = removeSuffix(javaSuperClass.getName(), SERVICE_SUFFIX);
            Name baseTypeName = parseJavaName(javaSuperClassName);
            assignService(baseTypeName, service::setBase);
        }

        // Analyze the members:
        javaClass.getNestedClasses().forEach(x -> analyzeServiceMember(x, service));
        javaClass.getMethods().forEach(x -> analyzeServiceMember(x, service));

        // Add the type to the model:
        model.addService(service);

        // Check if this should be the root of the tree of services of the model:
        if (isAnnotatedWith(javaClass, ModelAnnotations.ROOT)) {
            Service root = model.getRoot();
            if (root != null) {
                System.err.println(
                        "The current root \"" + root.getName() + "\" will be replaced with \"" + service.getName() + "\"."
                );
            }
            model.setRoot(service);
        }
    }

    private void analyzeServiceMember(JavaMethod javaMethod, Service service) {
        if (isAnnotatedWith(javaMethod, ModelAnnotations.SERVICE)) {
            analyzeServiceLocator(javaMethod, service);
        }
    }

    private void analyzeServiceMember(JavaClass javaClass, Service service) {
        analyzeMethod(javaClass, service);
    }

    private void analyzeMethod(JavaClass javaClass, Service service) {
        // Create the method:
        Method method = new Method();
        analyzeName(javaClass, method);
        analyzeAnnotations(javaClass, method);
        analyzeDocumentation(javaClass, method);

        // Analyze the members:
        javaClass.getMethods().forEach(x -> analyzeMethodMember(x, method));

        // Add the member to the service:
        method.setDeclaringService(service);
        service.addMethod(method);
    }

    private void analyzeMethodMember(JavaMethod javaMethod, Method method) {
        if (isAnnotatedWith(javaMethod, ModelAnnotations.IN) || isAnnotatedWith(javaMethod, ModelAnnotations.OUT)) {
            analyzeParameter(javaMethod, method);
        }
        if (isAnnotatedWith(javaMethod, ModelAnnotations.REQUIRED) || isAnnotatedWith(javaMethod, ModelAnnotations.ALLOWED)) {
            analyzeConstraint(javaMethod, method);
        }
    }

    private void analyzeParameter(JavaMethod javaMethod, Method method) {
        // Create the parameter:
        Parameter parameter = new Parameter();
        analyzeName(javaMethod, parameter);
        analyzeAnnotations(javaMethod, parameter);
        analyzeDocumentation(javaMethod, parameter);

        // Get the direction:
        if (isAnnotatedWith(javaMethod, ModelAnnotations.IN)) {
            parameter.setIn(true);
        }
        if (isAnnotatedWith(javaMethod, ModelAnnotations.OUT)) {
            parameter.setOut(true);
        }

        // Get the type:
        assignTypeReference(javaMethod.getReturns(), parameter::setType);

        // Get the default value:
        String javaValue = javaMethod.getSourceCode();
        if (javaValue != null && !javaValue.isEmpty()) {
            Expression expression = analyzeExpression(javaValue);
            parameter.setDefaultValue(expression);
        }

        // Add the parameter to the method:
        parameter.setDeclaringMethod(method);
        method.addParameter(parameter);
    }

    private void analyzeConstraint(JavaMethod javaMethod, Method method) {
        // Create the constraint:
        Constraint constraint = new Constraint();
        analyzeName(javaMethod, constraint);
        analyzeAnnotations(javaMethod, constraint);
        analyzeDocumentation(javaMethod, constraint);

        // Get the direction:
        if (isAnnotatedWith(javaMethod, ModelAnnotations.IN)) {
            constraint.setIn(true);
        }
        if (isAnnotatedWith(javaMethod, ModelAnnotations.OUT)) {
            constraint.setOut(true);
        }

        // Get the source:
        String source = javaMethod.getSourceCode();
        constraint.setSource(source);

        // Remember to analyze the constraint source once the types and services have been completely defined:
        undefinedConstraints.add(constraint);

        // Add the constraint to the method:
        constraint.setDeclaringMethod(method);
        method.addConstraint(constraint);
    }

    private void analyzeServiceLocator(JavaMethod javaMethod, Service service) {
        // Create the locator:
        Locator locator = new Locator();
        analyzeName(javaMethod, locator);
        analyzeAnnotations(javaMethod, locator);
        analyzeDocumentation(javaMethod, locator);

        // Analyze the parameters:
        javaMethod.getParameters().forEach(x -> analyzeLocatorParameter(x, locator));

        // Get the referenced service:
        assignServiceReference(javaMethod.getReturns(), locator::setService);

        // Add the parameter to the method:
        service.addLocator(locator);
    }

    private void analyzeLocatorParameter(JavaParameter javaParameter, Locator locator) {
        // Create the parameter:
        Parameter parameter = new Parameter();
        analyzeName(javaParameter, parameter);
        analyzeAnnotations(javaParameter, parameter);
        analyzeDocumentation(javaParameter, parameter);

        // Get the type:
        assignTypeReference(javaParameter.getJavaClass(), parameter::setType);

        // Add the parameter to the locator:
        locator.addParameter(parameter);
    }

    private void analyzeModule(JavaClass javaClass, Type type) {
        analyzeModule(javaClass, type::setModule);
    }

    private void analyzeModule(JavaClass javaClass, Service service) {
        analyzeModule(javaClass, service::setModule);
    }

    private void analyzeModule(JavaClass javaClass, Consumer<Module> moduleSetter) {
        String javaName = javaClass.getPackageName();
        Name name = NameParser.parseUsingSeparator(javaName, '.');
        Module module = model.getModule(name);
        if (module == null) {
            module = new Module();
            module.setModel(model);
            module.setName(name);
            model.addModule(module);
        }
        moduleSetter.accept(module);
    }

    private void analyzeAnnotations(JavaAnnotatedElement javaElement, Concept concept) {
        // Model annotations like @Type and @Service shouldn't be considered as annotations applied to concepts so
        // we need to check the package name and exclude them:
        javaElement.getAnnotations().stream()
             .filter(x -> !Objects.equals(x.getType().getPackageName(), Root.class.getPackage().getName()))
             .forEach(x -> this.analyzeAnnotation(x, concept));
    }

    @SuppressWarnings("unchecked")
    private void analyzeAnnotation(JavaAnnotation javaAnnotation, Concept concept) {
        // Create the annotation:
        Annotation annotation = new Annotation();
        analyzeName(javaAnnotation, annotation);

        // Get the parameters and their values:
        Map<String, Object> parameters = javaAnnotation.getNamedParameterMap();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            AnnotationParameter parameter = new AnnotationParameter();
            parameter.setName(NameParser.parseUsingCase(key));

            // Get the values of the parameter:
            List<String> values = new ArrayList<>();
            if (value instanceof List) {
                values.addAll((List<String>) value);
            }
            else {
                values.add(value.toString());
            }

            // QDox returns the values of parameters surrounded by double quotes. This is probably a bug, but we need
            // to live with it, so we need to remove them.
            for (int i = 0; i < values.size(); i++) {
                String current = values.get(i);
                if (current.startsWith("\"") && current.endsWith("\"")) {
                    current = current.substring(1, current.length() - 1);
                    values.set(i, current);
                }
            }

            // Set the values of the parameter:
            parameter.addValues(values);

            annotation.addParameter(parameter);
        }

        // Add the annotation to the concept:
        concept.addAnnotation(annotation);
    }

    private void analyzeName(JavaClass javaClass, Service service) {
        // Get the name of the Java class:
        String javaName = javaClass.getName();
        javaName = removeSuffix(javaName, SERVICE_SUFFIX);

        // Parse the Java name and assign it to the concept:
        Name name = parseJavaName(javaName);
        service.setName(name);
    }

    private void analyzeName(JavaClass javaClass, Concept concept) {
        // Get the name of the Java class:
        String javaName = javaClass.getName();

        // Parse the Java name and assign it to the concept:
        Name name = parseJavaName(javaName);
        concept.setName(name);
    }

    private void analyzeName(JavaField javaField, Concept concept) {
        // Fields of classes are parsed using case, but enum values are also represented as fields and they need to be
        // parsed using underscore as the separator:
        String javaName = javaField.getName();
        Name name;
        if (javaField.isEnumConstant()) {
            name = NameParser.parseUsingSeparator(javaName, '_');
        }
        else {
            name = parseJavaName(javaName);
        }
        concept.setName(name);
    }

    private void analyzeName(JavaMethod javaMethod, Concept concept) {
        String javaName = javaMethod.getName();
        Name name = parseJavaName(javaName);
        concept.setName(name);
    }

    private void analyzeName(JavaParameter javaParameter, Concept concept) {
        String javaName = javaParameter.getName();
        Name name = parseJavaName(javaName);
        concept.setName(name);
    }

    private void analyzeName(JavaAnnotation javaAnnotation, Annotation annotation) {
        String javaName = javaAnnotation.getType().getName();
        Name name = parseJavaName(javaName);
        annotation.setName(name);
    }

    private void analyzeDocumentation(JavaAnnotatedElement javaElement, Concept concept) {
        // Copy the text of the documentation (without the doclet tags):
        String javaComment = javaElement.getComment();
        if (javaComment != null) {
            javaComment = javaComment.trim();
            if (!javaComment.isEmpty()) {
                concept.setDoc(javaComment);
            }
        }

        // Make annotations for the javadoc tags:
        javaElement.getTags().stream().forEach(docTag -> {
            this.analyzeDocletTag(docTag, concept);
        });

    }

    private void analyzeDocletTag(DocletTag docTag, Concept concept) {
        // Calculate the name:
        Name name = NameParser.parseUsingCase(docTag.getName());

        // Create the annotation if it doesn't exist in the concept yet:
        Annotation annotation = concept.getAnnotation(name);
        if (annotation == null) {
            annotation = new Annotation();
            annotation.setName(name);
            concept.addAnnotation(annotation);
        }

        // Create the "value" parameter if it doesn't exist yet:
        String value = docTag.getValue();
        if (value != null) {
            value = value.trim();
            if (!value.isEmpty()) {
                AnnotationParameter parameter = annotation.getParameter(VALUE);
                if (parameter == null) {
                    parameter = new AnnotationParameter();
                    parameter.setName(VALUE);
                    annotation.addParameter(parameter);
                }
                parameter.addValue(docTag.getValue());
            }
        }
    }

    private void analyzeSource(JavaModel javaModel, Concept concept) {
        String javaSource = javaModel.getCodeBlock();
        if (javaSource != null && !javaSource.isEmpty()) {
            concept.setSource(javaSource);
        }
    }

    /**
     * Finds the type corresponding to the given reference and assigns it using the given setter. If there is no type
     * corresponding to the given reference yet then a new undefined type will be created and assigned, and it will be
     * remembered so that it can later be replaced with the real type.
     *
     * @param javaClass the reference to the type to find
     * @param typeSetter the setter used to assign the type
     */
    private void assignTypeReference(JavaClass javaClass, TypeSetter typeSetter) {
        String javaTypeName = javaClass.getName();
        Name typeName;
        switch (javaTypeName) {
        case "Boolean":
        case "bool":
            typeName = model.getBooleanType().getName();
            break;
        case "Double":
        case "Float":
        case "double":
        case "float":
            typeName = model.getDecimalType().getName();
            break;
        default:
            typeName = parseJavaName(javaTypeName);
        }
        if (javaClass.isArray()) {
            ListType listType = listTypes.get(typeName);
            if (listType == null) {
                listType = new ListType();
                assignType(typeName, listType::setElementType);
                listTypes.put(typeName, listType);
            }
            typeSetter.accept(listType);
        }
        else {
            assignType(typeName, typeSetter);
        }
    }

    /**
     * Finds the type corresponding to the given name and assigns it using the given setter. If there is no type
     * corresponding to the given name yet then a new undefined type will be created and assigned, and it will be
     * remembered so that it can later be replaced with the real type.
     *
     * @param typeName the name of the type to find
     * @param typeSetter the setter used to assign the type
     */
    private void assignType(Name typeName, TypeSetter typeSetter) {
        // First try to find a type that has already been defined:
        Type type = model.getType(typeName);

        // If the type hasn't been defined then we create need to create a dummy type
        if (type == null) {
            type = new UndefinedType();
            type.setName(typeName);
        }

        // If we are returning an undefined type then we need to to remember to replace it later, saving the name of
        // the type and the setter provided by the calller:
        if (type instanceof UndefinedType) {
            TypeUsage typeUsage = new TypeUsage();
            typeUsage.setName(typeName);
            typeUsage.setSetter(typeSetter);
            undefinedTypeUsages.add(typeUsage);
        }

        // Assign the type:
        if (typeSetter != null) {
            typeSetter.accept(type);
        }
    }

    /**
     * Finds the service corresponding to the given reference and assigns it using the given setter. If there is no
     * service corresponding to the given reference yet then a new undefined service  will be created and assigned, and
     * it will be remembered so that it can later be replaced with the real service.
     *
     * @param javaClass the reference to the service to find
     * @param setter the setter used to assign the type
     */
    private void assignServiceReference(JavaClass javaClass, ServiceSetter setter) {
        // Get the name of the Java class:
        String javaName = javaClass.getName();
        javaName = removeSuffix(javaName, SERVICE_SUFFIX);

        // Parse the name and assign it to the service:
        Name name = parseJavaName(javaName);
        assignService(name, setter);
    }

    /**
     * Finds the service corresponding to the given name and assigns it using the given setter. If there is no service
     * corresponding to the given name yet then a new undefined service will be created and assigned, and it will be
     * remembered so that it can later be replaced with the real service.
     *
     * @param serviceName the name of the service to find
     * @param serviceSetter the setter used to assign the service
     */
    private void assignService(Name serviceName, ServiceSetter serviceSetter) {
        // First try to find a service that has already been defined:
        Service service = model.getService(serviceName);

        // If the service hasn't been defined then we create need to create a dummy service:
        if (service == null) {
            service = new UndefinedService();
            service.setName(serviceName);
        }

        // If we are returning an undefined service then we need to to remember to replace it later, saving the name of
        // the service and the setter provided by the caller:
        if (service instanceof UndefinedService) {
            ServiceUsage serviceUsage = new ServiceUsage();
            serviceUsage.setName(serviceName);
            serviceUsage.setSetter(serviceSetter);
            undefinedServiceUsages.add(serviceUsage);
        }

        // Assign the service:
        if (serviceSetter != null) {
            serviceSetter.accept(service);
        }
    }

    /**
     * Creates a document with the given name and, populates it with the content read from the given input stream, and
     * adds it to the model.
     *
     * @param file the name of file containing the document, including the extension
     * @param in the input stream that will be used to populate the document
     * @throws IOException if something fails while reading the content of the document
     */
    private void analyzeDocument(String file, InputStream in) throws IOException {
        // Create the document:
        Document document = new Document();

        // Remove the extension from the file name:
        file = FilenameUtils.getBaseName(file);

        // The name of the document can contain a prefix to explicitly indicate the order of the document relative to
        // the other documents of the model. This prefix should be separated from the rest of the name using a dash, and
        // that dash should be ignored.
        String prefix = null;
        int index = file.indexOf('-');
        if (index > 0) {
            prefix = file.substring(0, index);
            file = file.substring(index + 1);
        }
        Name name = NameParser.parseUsingCase(file);
        if (prefix != null && !prefix.isEmpty()) {
            List<String> words = name.getWords();
            words.add(0, prefix);
            name.setWords(words);
            if (Character.isAlphabetic(prefix.charAt(0))) {
                document.setAppendix(true);
            }
        }
        document.setName(name);

        // Read the source of the document:
        String source = IOUtils.toString(in, StandardCharsets.UTF_8);
        document.setSource(source);

        // Add the document to the model:
        model.addDocument(document);
    }

    /**
     * Finds the references to unresolved types and replace them with the real type definitions.
     */
    private void fixUndefinedTypeUsages() {
        for (TypeUsage usage : undefinedTypeUsages) {
            Name name = usage.getName();
            Type type = model.getType(name);
            if (type == null) {
                System.err.println("Can't find type for name \"" + name + "\".");
                continue;
            }
            TypeSetter setter = usage.getSetter();
            setter.accept(type);
        }
    }

    /**
     * Finds the references to unresolved services and replace them with the real service definitions.
     */
    private void fixUndefinedServiceUsages() {
        for (ServiceUsage usage : undefinedServiceUsages) {
            Name name = usage.getName();
            Service service = model.getService(name);
            if (service == null) {
                System.err.println("Can't find service for name \"" + name + "\".");
                continue;
            }
            ServiceSetter setter = usage.getSetter();
            setter.accept(service);
        }
    }

    /**
     * Parse the sources of the constraints.
     */
    private void parseConstraints() {
        undefinedConstraints.stream().forEach(this::parseConstraint);
    }

    /**
     * Parse the source of the given constraint.
     */
    private void parseConstraint(Constraint constraint) {
        ConstraintAnalyzer analyzer = new ConstraintAnalyzer();
        analyzer.setModel(model);
        analyzer.setMethod(constraint.getDeclaringMethod());
        analyzer.setConstraint(constraint);
        analyzer.analyzeSource(constraint.getSource());
    }

    private Expression analyzeExpression(String javaExpression) {
        // TODO: Analyze the expression tree.
        Expression expression = null;
        // expression.setValue(javaExpression);
        return expression;
    }

    /**
     * Check if the given Java element is annotated with the given annotation.
     *
     * @param javaElement the parsed element to check
     * @param annotationName the name of the annotation to check
     * @return {@code true} iif the given class is annotated with the given annotation
     */
    private boolean isAnnotatedWith(JavaAnnotatedElement javaElement, String annotationName) {
        for (JavaAnnotation annotation : javaElement.getAnnotations()) {
            JavaClass currentType = annotation.getType();
            String currentName = currentType.getPackageName() + "." + currentType.getName();
            if (Objects.equals(currentName, annotationName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the given string ends with the given suffixe, and if it does removes it.
     *
     * @param text the string to check
     * @param suffix the suffix to check/remove
     */
    private String removeSuffix(String text, String suffix) {
        if (text.endsWith(suffix)) {
            text = text.substring(0, text.length() - suffix.length());
        }
        return text;
    }

    /**
     * Creates a model name from a Java name, doing any processing that is required, for example removing the prefixes
     * or suffixes that are used to avoid conflicts with Java reserved words.
     */
    private Name parseJavaName(String text) {
        // Remove the underscore prefixes and suffixes, as they only make sense to avoid conflicts with Java reserved
        // words and they aren't needed in the model:
        while (text.startsWith("_")) {
            text = text.substring(1);
        }
        while (text.endsWith("_")) {
            text = text.substring(0, text.length() - 1);
        }

        // Once the name is clean it can be parsed:
        return NameParser.parseUsingCase(text);
    }
}
