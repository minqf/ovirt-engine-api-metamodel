/*
Copyright (c) 2015 Red Hat, Inc.

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

package org.ovirt.api.metamodel.tool;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.joining;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Result;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.ovirt.api.metamodel.concepts.Concept;
import org.ovirt.api.metamodel.concepts.EnumType;
import org.ovirt.api.metamodel.concepts.EnumValue;
import org.ovirt.api.metamodel.concepts.ListType;
import org.ovirt.api.metamodel.concepts.Model;
import org.ovirt.api.metamodel.concepts.Name;
import org.ovirt.api.metamodel.concepts.NameParser;
import org.ovirt.api.metamodel.concepts.Parameter;
import org.ovirt.api.metamodel.concepts.PrimitiveType;
import org.ovirt.api.metamodel.concepts.Service;
import org.ovirt.api.metamodel.concepts.StructMember;
import org.ovirt.api.metamodel.concepts.StructType;
import org.ovirt.api.metamodel.concepts.Type;
import org.ovirt.api.metamodel.runtime.xml.XmlWriter;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * This class takes a model and an input XML schema file and modifies it adding (at the end) the XML schema elements
 * corresponding to the model.
 */
@ApplicationScoped
public class SchemaGenerator {
    // URI and prefix for the XML schema namespace:
    private static final String XS_URI = "http://www.w3.org/2001/XMLSchema";
    private static final String XS_PREFIX = "xs";

    // URI and prefix for the JAXB namespace:
    private static final String JAXB_URI = "http://java.sun.com/xml/ns/jaxb";
    private static final String JAXB_PREFIX = "jaxb";

    // Reference to the object used to calculate XML schema names:
    @Inject private Names names;
    @Inject private SchemaNames schemaNames;

    // Types that need to extends the "BaseResource" complex type:
    private Type identifiedType;
    private Type apiType;

    // Exceptions to the rules to calculate struct member type names:
    private static final Map<Name, Map<Name, String>> MEMBER_SCHEMA_TYPE_NAME_EXCEPTIONS = new HashMap<>();

    private static void addMemberSchemaTypeNameException(String type, String member, String exception) {
        Name typeName = NameParser.parseUsingSeparator(type, '-');
        Name memberName = NameParser.parseUsingSeparator(member, '-');
        Map<Name, String> typeExceptions = MEMBER_SCHEMA_TYPE_NAME_EXCEPTIONS.get(typeName);
        if (typeExceptions == null) {
            typeExceptions = new HashMap<>();
            MEMBER_SCHEMA_TYPE_NAME_EXCEPTIONS.put(typeName, typeExceptions);
        }
        typeExceptions.put(memberName, exception);
    }

    private static String getMemberSchemaTypeNameException(Name typeName, Name memberName) {
        Map<Name, String> typeExceptions = MEMBER_SCHEMA_TYPE_NAME_EXCEPTIONS.get(typeName);
        if (typeExceptions == null) {
            return null;
        }
        return typeExceptions.get(memberName);
    }

    static {
        addMemberSchemaTypeNameException("disk", "actual-size", "xs:long");
        addMemberSchemaTypeNameException("disk", "provisioned-size", "xs:long");
        addMemberSchemaTypeNameException("disk", "initial-size", "xs:long");
        addMemberSchemaTypeNameException("gluster-client", "bytes-read", "xs:long");
        addMemberSchemaTypeNameException("gluster-client", "bytes-written", "xs:long");
        addMemberSchemaTypeNameException("host", "max-scheduling-memory", "xs:long");
        addMemberSchemaTypeNameException("host", "memory", "xs:long");
        addMemberSchemaTypeNameException("host-nic", "speed", "xs:long");
        addMemberSchemaTypeNameException("logical-unit", "discard-max-size", "xs:long");
        addMemberSchemaTypeNameException("logical-unit", "size", "xs:long");
        addMemberSchemaTypeNameException("memory-policy", "guaranteed", "xs:long");
        addMemberSchemaTypeNameException("memory-policy", "max", "xs:long");
        addMemberSchemaTypeNameException("numa-node", "memory", "xs:long");
        addMemberSchemaTypeNameException("quota-cluster-limit", "memory-limit", "xs:double");
        addMemberSchemaTypeNameException("quota-cluster-limit", "memory-usage", "xs:double");
        addMemberSchemaTypeNameException("quota-storage-limit", "limit", "xs:long");
        addMemberSchemaTypeNameException("quota-storage-limit", "usage", "xs:double");
        addMemberSchemaTypeNameException("statistic", "kind", "StatisticKind");
        addMemberSchemaTypeNameException("statistic", "type", "ValueType");
        addMemberSchemaTypeNameException("statistic", "unit", "StatisticUnit");
        addMemberSchemaTypeNameException("storage-domain", "available", "xs:long");
        addMemberSchemaTypeNameException("storage-domain", "used", "xs:long");
        addMemberSchemaTypeNameException("storage-domain", "committed", "xs:long");
        addMemberSchemaTypeNameException("ticket", "expiry", "xs:unsignedInt");
        addMemberSchemaTypeNameException("vm-base", "memory", "xs:long");
    }

    // Types that don't need a collection type:
    private static final Set<Name> TYPES_WITHOUT_COLLECTION = new HashSet<>();

    private static void addTypeWithoutCollection(String name) {
        TYPES_WITHOUT_COLLECTION.add(NameParser.parseUsingCase(name));
    }

    static {
        addTypeWithoutCollection("Action");
    }

    // We will need an XML parser:
    private DocumentBuilder parser;

    // The reference to the model:
    private Model model;

    // The input and output schema files:
    private File inFile;
    private File outFile;

    // The object used to write the XML document:
    private XmlWriter writer;

    @PostConstruct
    private void init() {
        // Create the XML parser:
        try {
            parser = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        }
        catch (ParserConfigurationException exception) {
            throw new RuntimeException("Can't create XML parser.", exception);
        }
    }

    /**
     * Set the input XML schema file.
     */
    public void setInFile(File newInFile) {
        inFile = newInFile;
    }

    /**
     * Set the output XML schema file.
     */
    public void setOutFile(File newOutFile) {
        outFile = newOutFile;
    }

    public void generate(Model model) {
        // Save the reference to the model:
        this.model = model;

        // Find the identified type:
        Name identifiedTypeName = NameParser.parseUsingCase("Identified");
        identifiedType = model.getType(identifiedTypeName);
        if (identifiedType == null) {
            throw new RuntimeException(
                "Can't find the identified type \"" + identifiedTypeName + "\""
            );
        }
        Name apiTypeName = NameParser.parseUsingCase("Api");
        apiType = model.getType(apiTypeName);
        if (apiType == null) {
            throw new RuntimeException(
                "Can't find the API type \"" + apiTypeName + "\""
            );
        }

        // Parse the input XML schema:
        Document inSchema;
        try {
            inSchema = parser.parse(inFile);
        }
        catch (Exception exception) {
            throw new RuntimeException(
                "Can't parse input XML schema from file \"" + inFile.getAbsolutePath() + "\"",
                exception
            );
        }

        // Generate the new XML schema:
        Document newSchema = generateSchema();

        // Add all the elements of the new schema at the end of the input schema:
        Element inRoot = inSchema.getDocumentElement();
        Element newRoot = newSchema.getDocumentElement();
        NodeList newChildren = newRoot.getChildNodes();
        for (int i = 0; i < newChildren.getLength(); i++) {
            Node newChild = newChildren.item(i);
            Node importedChild = inSchema.importNode(newChild, true);
            inRoot.appendChild(importedChild);
        }

        // Write the output XML schema:
        File outDir = outFile.getParentFile();
        if (!outDir.exists()) {
            if (!outDir.mkdirs()) {
                throw new RuntimeException(
                    "Can't create output XML directory \"" + outDir.getAbsolutePath() + "\"."
                );
            }
        }
        Transformer transformer;
        try {
            transformer = TransformerFactory.newInstance().newTransformer();
        }
        catch (TransformerConfigurationException exception) {
            throw new RuntimeException(
                "Can't create XSLT transformer.",
                exception
            );
        }
        Source transformerIn = new DOMSource(inSchema);
        Result transformerOut = new StreamResult(outFile);
        try {
            transformer.transform(transformerIn, transformerOut);
        }
        catch (TransformerException exception) {
            throw new RuntimeException(
                "Can't write output XML schema to file \"" + outFile.getAbsolutePath() + "\".",
                exception
            );
        }
    }

    private Document generateSchema() {
        Document document = parser.newDocument();
        DOMResult result = new DOMResult(document);
        try (XmlWriter tmp = new XmlWriter(result, true)) {
            writer = tmp;
            writer.setPrefix(XS_PREFIX, XS_URI);
            writer.setPrefix(JAXB_PREFIX, JAXB_URI);
            writeSchema();
            return document;
        }
    }

    private void writeSchema() {
        // Header:
        writer.writeStartElement(XS_URI, "schema");
        writer.writeAttribute("version", "1.0");

        // Generate the action types:
        writeActionTypes();

        // Find the struct and enum types:
        List<StructType> structTypes = new ArrayList<>();
        List<EnumType> enumTypes = new ArrayList<>();
        for (Type type : model.getTypes()) {
            if (type instanceof StructType && type != identifiedType) {
                structTypes.add((StructType) type);
            }
            if (type instanceof EnumType) {
                enumTypes.add((EnumType) type);
            }
        }

        // Write the XML schema group of elements that are used by the capabilities resource to list the possible
        // values of the enum types. Eventually the complete capabilities elements will be generated, but for now
        // it is written manually and int includes this group.
        writeEnumValues(enumTypes);

        // Write the XML schema for the enum types:
        enumTypes.forEach(this::writeEnumType);

        // Write the XML schema for the struct types:
        structTypes.forEach(this::writeStructType);

        // Footer:
        writer.writeEndElement();
    }

    private void writeEnumValues(List<EnumType> types) {
        // Write the group used by the capabilities resource to report that contains the possible values for all the
        // enum types:
        writer.writeStartElement(XS_URI, "group");
        writer.writeAttribute("name", "EnumValues");
        writer.writeStartElement(XS_URI, "sequence");
        for (EnumType type : types) {
            Name name = type.getName();
            Name plural = names.getPlural(name);
            writer.writeStartElement(XS_URI, "element");
            writer.writeAttribute("name", schemaNames.getSchemaTagName(plural));
            writer.writeAttribute("type", getSchemaEnumTypeValuesName(name));
            writer.writeAttribute("minOccurs", "1");
            writer.writeAttribute("maxOccurs", "1");
            writer.writeEndElement();
        }
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeLine();

        // For each enum type write the complex type used by the capabilities resource to report the possible values
        // for that enum type:
        for (EnumType type : types) {
            Name name = type.getName();
            writer.writeStartElement(XS_URI, "complexType");
            writer.writeAttribute("name", getSchemaEnumTypeValuesName(name));
            writer.writeStartElement(XS_URI, "sequence");
            writer.writeStartElement(XS_URI, "element");
            writer.writeAttribute("name", schemaNames.getSchemaTagName(name));
            writer.writeAttribute("type", schemaNames.getSchemaTypeName(name));
            writer.writeAttribute("minOccurs", "0");
            writer.writeAttribute("maxOccurs", "unbounded");
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeEndElement();
            writer.writeLine();
        }
    }

    private void writeStructType(StructType type) {
        // Get the name of the type, and its plural:
        Name typeName = type.getName();
        Name typePlural = names.getPlural(typeName);

        // Check if this type is an identified type, one that can appear as the root of a valid XML document, as in that
        // case the complex types must extend "BaseResource" and "BaseResources":
        boolean isRoot = type.isExtension(identifiedType) || type == apiType;

        // Tag for the entity:
        writer.writeStartElement(XS_URI, "element");
        writer.writeAttribute("name", schemaNames.getSchemaTagName(typeName));
        writer.writeAttribute("type", schemaNames.getSchemaTypeName(type));
        writer.writeEndElement();
        writer.writeLine();

        // Determine if the complex type is an extension of other complex type:
        String baseComplexTypeName = null;
        Type baseType = type.getBase();
        if (baseType != null && baseType != identifiedType) {
            baseComplexTypeName = schemaNames.getSchemaTypeName(baseType);
        }
        else {
            if (isRoot) {
                baseComplexTypeName = "BaseResource";
            }
        }

        // Complex type for the entity:
        writer.writeStartElement(XS_URI, "complexType");
        writer.writeAttribute("name", schemaNames.getSchemaTypeName(type));
        if (baseComplexTypeName != null) {
            writer.writeStartElement(XS_URI, "complexContent");
            writer.writeStartElement(XS_URI, "extension");
            writer.writeAttribute("base", baseComplexTypeName);
        }
        writeStructMembers(type);
        if (baseComplexTypeName != null) {
            writer.writeEndElement();
            writer.writeEndElement();
        }
        writer.writeEndElement();
        writer.writeLine();

        if (!TYPES_WITHOUT_COLLECTION.contains(type.getName())) {
            // Tag for the collection:
            writer.writeStartElement(XS_URI, "element");
            writer.writeAttribute("name", schemaNames.getSchemaTagName(typePlural));
            writer.writeAttribute("type", schemaNames.getSchemaTypeName(typePlural));
            writer.writeEndElement();
            writer.writeLine();

            // Complex type for the collection:
            writer.writeStartElement(XS_URI, "complexType");
            writer.writeAttribute("name", schemaNames.getSchemaTypeName(typePlural));
            if (isRoot) {
                writer.writeStartElement(XS_URI, "complexContent");
                writer.writeStartElement(XS_URI, "extension");
                writer.writeAttribute("base", "BaseResources");
            }
            writer.writeStartElement(XS_URI, "sequence");
            writer.writeStartElement(XS_URI, "element");
            writer.writeAttribute("ref", schemaNames.getSchemaTagName(typeName));
            writer.writeAttribute("minOccurs", "0");
            writer.writeAttribute("maxOccurs", "unbounded");
            writeJaxbProperty(schemaNames.getSchemaTypeName(typePlural));
            writer.writeEndElement();
            writer.writeEndElement();
            if (isRoot) {
                writer.writeEndElement();
                writer.writeEndElement();
            }
            writer.writeEndElement();
            writer.writeLine();
        }
    }

    private void writeStructMembers(StructType type) {
        // Write the attributes and links that are represented as XML elements:
        writer.writeStartElement(XS_URI, "sequence");
        type.declaredAttributes()
            .filter(x -> !schemaNames.isRepresentedAsAttribute(x.getName()))
            .sorted()
            .forEach(this::writeStructMemberAsElement);
        type.declaredLinks()
            .filter(x -> !schemaNames.isRepresentedAsAttribute(x.getName()))
            .sorted()
            .forEach(this::writeStructMemberAsElement);
        writer.writeEndElement();

        // Write the attributes and links that are represented as XML attributes, those need to go outside of the
        // "sequence" that contains the XML elements:
        type.declaredAttributes()
            .filter(x -> schemaNames.isRepresentedAsAttribute(x.getName()))
            .sorted()
            .forEach(this::writeStructMemberAsAttribute);
        type.declaredLinks()
            .filter(x -> schemaNames.isRepresentedAsAttribute(x.getName()))
            .sorted()
            .forEach(this::writeStructMemberAsAttribute);
    }

    private void writeStructMemberAsAttribute(StructMember member) {
        // Get the name and the type:
        Name memberName = member.getName();
        Type memberType = member.getType();

        // Write the attribute definition:
        writer.writeStartElement(XS_URI, "attribute");
        writer.writeAttribute("name", schemaNames.getSchemaTagName(memberName));
        writer.writeAttribute("type", getMemberSchemaTypeName(member.getDeclaringType(), memberType, memberName));
        writer.writeEndElement();
    }

    private void writeStructMemberAsElement(StructMember member) {
        // Get the name and the type:
        Name memberName = member.getName();
        Type memberType = member.getType();

        // Calculate the singular of the name:
        Name singular = names.getSingular(memberName);

        // Write the element definition:
        writer.writeStartElement(XS_URI, "element");
        writer.writeAttribute("name", schemaNames.getSchemaTagName(memberName));
        writer.writeAttribute("minOccurs", "0");
        writer.writeAttribute("maxOccurs", "1");
        if (memberType instanceof ListType) {
            ListType listType = (ListType) memberType;
            Type elementType = listType.getElementType();
            String elementTypeName = getMemberSchemaTypeName(member.getDeclaringType(), elementType, memberName);
            if (elementType instanceof PrimitiveType || elementType instanceof EnumType) {
                // Attributes that are lists of XML schema scalar types (xs:string, xs:int, etc) are represented with a
                // wrapper element named like the attribute, and then a sequence of elements named like the attribute
                // but in singular. For example, if the name of the attribute is "dns_servers" and the values are
                // strings it will be represented as follows:
                //
                // <dns_servers>
                //   <dns_server>a.b.c.d</dns_server>
                //   <dns_server>e.f.g.h</dns_server>
                // </dns_servers>
                //
                // The generated XML schema will look like this:
                //
                // <xs:element name="dns_servers" minOccurs="0" maxOccurs="1">
                //   <xs:complexType>
                //     <xs:annotation>
                //       <xs:appinfo>
                //         <jaxb:class name="DnsServersList"/>
                //       </xs:appinfo>
                //     </xs:annotation>
                //     <xs:sequence>
                //       <xs:element name="dns_servers" type="xs:string" minOccurs="0" maxOccurs="unbounded">
                //         <xs:annotation>
                //           <xs:appinfo>
                //             <jaxb:class property="DnsServers"/>
                //           </xs:appinfo>
                //         </xs:annotation>
                //       </xs:element>
                //     </xs:sequence>
                //   </xs:complexType>
                // </xs:element>
                writer.writeStartElement(XS_URI, "complexType");
                writeJaxbClass(schemaNames.getSchemaTypeName(memberName) + "List");
                writer.writeStartElement(XS_URI, "sequence");
                writer.writeStartElement(XS_URI, "element");
                writer.writeAttribute("name", schemaNames.getSchemaTagName(singular));
                writer.writeAttribute("type", elementTypeName);
                writer.writeAttribute("minOccurs", "0");
                writer.writeAttribute("maxOccurs", "unbounded");
                writeJaxbProperty(schemaNames.getSchemaTypeName(memberName));
                writer.writeEndElement();
                writer.writeEndElement();
                writer.writeEndElement();
            }
            else {
                // Attributes of lists of XML schema complex types (Disk, Vm, etc) are represented with with a wrapper
                // element named like the attribute, and then a sequence of elements named like the type of the element.
                // For example, if the attribute is "primary_disks" and the values are objects of type "Disk" then it
                // will be represented as follows:
                //
                // <primary_disks>
                //   <disk>
                //     ...
                //   </disk>
                // </primary_disks>
                //
                // The generated XML schema will look like this:
                //
                // <xs:element name="primary_disks" type="Disks" minOccurs="0" maxOccurs="1"/>
                //
                // Note that this assumes that a "Disks" complex type has been generated, which is always true.
                writer.writeAttribute("name", schemaNames.getSchemaTagName(memberName));
                writer.writeAttribute("type", schemaNames.getSchemaTypeName(names.getPlural(elementType.getName())));
                writer.writeAttribute("minOccurs", "0");
                writer.writeAttribute("maxOccurs", "1");
            }
        }
        else {
            writer.writeAttribute("name", schemaNames.getSchemaTagName(memberName));
            writer.writeAttribute("type", getMemberSchemaTypeName(member.getDeclaringType(), memberType, memberName));
            writer.writeAttribute("minOccurs", "0");
            writer.writeAttribute("maxOccurs", "1");
        }
        writer.writeEndElement();
    }

    private void writeEnumType(EnumType type) {
        // Get the enum values and sort them by name:
        List<EnumValue> values = new ArrayList<>(type.getValues());
        values.sort(comparing(Concept::getName));

        // Generate the XML schema enumerated type that will for attributes whose value is of this enum type:
        writer.writeStartElement(XS_URI, "simpleType");
        writer.writeAttribute("name", schemaNames.getSchemaTypeName(type));
        writer.writeStartElement(XS_URI, "restriction");
        writer.writeAttribute("base", "xs:string");
        for (EnumValue value : values) {
            writer.writeStartElement(XS_URI, "enumeration");
            writer.writeAttribute("value", getSchemaEnumValueName(value));
            writeJaxbProperty(getJavaEnumValueName(value));
            writer.writeEndElement();
        }
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeLine();
    }

    private void writeActionTypes() {
        // Write the "Actions" complex type:
        writer.writeStartElement(XS_URI, "complexType");
        writer.writeAttribute("name", "Actions");
        writer.writeStartElement(XS_URI, "sequence");
        writer.writeStartElement(XS_URI, "element");
        writer.writeAttribute("ref", "link");
        writer.writeAttribute("minOccurs", "0");
        writer.writeAttribute("maxOccurs", "unbounded");
        writeJaxbCustomization("property", "name", "Links");
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeLine();
    }

    private void writeJaxbClass(String value) {
        writeJaxbCustomization("class", "name", value);
    }

    private void writeJaxbProperty(String value) {
        writeJaxbCustomization("property", "name", value);
    }

    private void writeJaxbCustomization(String tag, String name, String value) {
        writer.writeStartElement(XS_URI, "annotation");
        writer.writeStartElement(XS_URI, "appinfo");
        writer.writeStartElement(JAXB_URI, tag);
        writer.writeAttribute(name, value);
        writer.writeEndElement();
        writer.writeEndElement();
        writer.writeEndElement();
    }

    private String getMemberSchemaTypeName(Type declaringType, Type memberType, Name memberName) {
        String exception = getMemberSchemaTypeNameException(declaringType.getName(), memberName);
        if (exception != null) {
            return exception;
        }
        return schemaNames.getSchemaTypeName(memberType);
    }

    private String getSchemaEnumTypeValuesName(Name name) {
        return schemaNames.getSchemaTypeName(name) + "Values";
    }

    private String getSchemaEnumValueName(EnumValue value) {
        return value.getName().words().map(String::toLowerCase).collect(joining("_"));
    }

    private String getJavaEnumValueName(EnumValue value) {
        return value.getName().words().map(String::toUpperCase).collect(joining("_"));
    }
}

