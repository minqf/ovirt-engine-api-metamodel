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

package org.ovirt.api.metamodel.runtime.xml;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * This class wraps the {@link XMLStreamReader} class so that the methods don't send checked exceptions, in order to
 * simplify its usage together with streams and lambdas.
 */
public class XmlReader implements AutoCloseable {
    // The wrapped XML reader:
    private XMLStreamReader reader;

    /**
     * Thread local used to store the date formats.
     */
    private static final ThreadLocal<SimpleDateFormat> DATE_FORMAT = new ThreadLocal<SimpleDateFormat>(){
        @Override
        protected SimpleDateFormat initialValue() {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format;
        }
    };

    /**
     * Creates an XML reader that will read from the given source.
     *
     * @param source the source where the document will be read from
     */
    public XmlReader(Source source) {
        init(source);
    }

    /**
     * Creates an XML reader that will read from the given stream, using UTF-8 as the encoding.
     *
     * @param in the stream where the document will be read from
     */
    public XmlReader(InputStream in) {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        Source source = new StreamSource(reader);
        init(source);
    }

    /**
     * Creates an XML reader that will read from the given reader.
     *
     * @param in the reader where the document will be read from
     */
    public XmlReader(Reader in) {
        Source source = new StreamSource(in);
        init(source);
    }

    /**
     * Creates a reader that will read from the given file, using UTF-8 as the encoding.
     *
     * @param file the file where the document will be written
     */
    public XmlReader(File file) {
        try {
            InputStream in = new FileInputStream(file);
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Source source = new StreamSource(reader);
            init(source);
        }
        catch (IOException exception) {
            throw new XmlException("Can't open file \"" + file.getAbsolutePath() + "\" for reading", exception);
        }
    }

    private void init(Source source) {
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            reader = factory.createXMLStreamReader(source);
        }
        catch (XMLStreamException exception) {
            throw new XmlException("Can't create XML reader", exception);
        }
    }

    /**
     * Returns the number of attributes of the current element.
     */
    public int getAttributeCount() {
        return reader.getAttributeCount();
    }

    /**
     * Returns the name of the attribute corresponding to the given index.
     *
     * @param index the index of the attribute
     */
    public String getAttributeLocalName(int index) {
        return reader.getAttributeLocalName(index);
    }

    /**
     * Returns the value of the attribute corresponding to the given index.
     *
     * @param index the index of the attribute
     */
    public String getAttributeValue(int index) {
        return reader.getAttributeValue(index);
    }

    /**
     * Returns the value of the attribute corresponding to the given name.
     *
     * @param name the name of the attribute
     */
    public String getAttributeValue(String name) {
        return reader.getAttributeValue(null, name);
    }

    /**
     * Gets the next parsing event.
     */
    public int next() {
        try {
            return reader.next();
        }
        catch (XMLStreamException exception) {
            throw new XmlException("Can't get next event", exception);
        }
    }

    /**
     * Returns the name of the current element.
     */
    public String getLocalName() {
        return reader.getLocalName();
    }

    /**
     * Returns the type of the current event.
     */
    public int getEventType() {
        return reader.getEventType();
    }

    /**
     * Jumps to the next start tag, end tag or end of document. Returns {@code true} if stopped at a start
     * tag, {@code false} otherwise.
     */
    public boolean forward() {
        for(;;) {
            switch (reader.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    return true;
                case XMLStreamConstants.END_ELEMENT:
                case XMLStreamConstants.END_DOCUMENT:
                    return false;
                default:
                    try {
                        reader.next();
                    }
                    catch (XMLStreamException exception) {
                        throw new XmlException("Can't get next event", exception);
                    }
            }
        }
    }

    /**
     * Skips the current element, and all the inner elements. The reader will be positioned at the event after the
     * the current one. For example, if the input text is {@code <root><current>...</current><next/></root>} and the
     * reader is positioned at the start of the {@code current} element, then the method will skip all the content
     * of the {@code current} element, and will leave the reader positioned at the start of the {@code next} element.
     */
    public void skip() {
        int depth = 0;
        for(;;) {
            switch (reader.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    depth++;
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    depth--;
                    if (depth <= 0) {
                        try {
                            reader.next();
                        }
                        catch (XMLStreamException exception) {
                            throw new XmlException("Can't get next event", exception);
                        }
                        return;
                    }
                    break;
                case XMLStreamConstants.END_DOCUMENT:
                    return;
            }
            try {
                reader.next();
            }
            catch (XMLStreamException exception) {
                throw new XmlException("Can't get next event", exception);
            }
        }
    }

    /**
     * Reads a boolean value from the given XML reader, assuming that the cursor is positioned at the start
     * element that contains the value of the boolean.
     */
    public boolean readBoolean() {
        return parseBoolean(readString());
    }

    /**
     * Reads an integer value from the given XML reader, assuming that the cursor is positioned at the start
     * element that contains the value of the integer.
     */
    public BigInteger readInteger() {
        return parseInteger(readString());
    }

    /**
     * Reads an decimal value from the given XML reader, assuming that the cursor is positioned at the start
     * element that contains the value of the decimal.
     */
    public BigDecimal readDecimal() {
        return parseDecimal(readString());
    }

    /**
     * Reads an string value from the given XML reader, assuming that the cursor is positioned at the start
     * element that contains the value of the string.
     */
    public String readString() {
        try {
            return reader.getElementText();
        }
        catch (XMLStreamException exception) {
            throw new XmlException("Can't get element text", exception);
        }
        finally {
            try {
                reader.next();
            }
            catch (XMLStreamException exception) {
                throw new XmlException("Can't get next event", exception);
            }
        }
    }

    /**
     * Reads a date value from the given XML reader, assuming that the cursor is positioned at the start
     * element that contains the value of the date.
     */
    public Date readDate() {
        return parseDate(readString());
    }

    /**
     * Reads an list of booleans elements with same element name, assuming that the cursor is positioned at the start
     * element that contains the value of the boolean and ends when different element name found.
     */
    public List<Boolean> readBooleans() {
        List<Boolean> booleans = new ArrayList<>();
        for (String string : readStrings()) {
            booleans.add(parseBoolean(string));
        }

        return booleans;
    }

    /**
     * Reads an list of integers elements with same element name, assuming that the cursor is positioned at the start
     * element that contains the value of the integers and ends when different element name found.
     */
    public List<BigInteger> readIntegers() {
        List<BigInteger> integers = new ArrayList<>();
        for (String string : readStrings()) {
            integers.add(parseInteger(string));
        }

        return integers;
    }

    /**
     * Reads an list of decimals elements with same element name, assuming that the cursor is positioned at the start
     * element that contains the value of the decimal and ends when different element name found.
     */
    public List<BigDecimal> readDecimals() {
        List<BigDecimal> decimals = new ArrayList<>();
        for (String string : readStrings()) {
            decimals.add(parseDecimal(string));
        }

        return decimals;
    }

    /**
     * Reads an list of dates elements with same element name, assuming that the cursor is positioned at the start
     * element that contains the value of the date and ends when different element name found.
     */
    public List<Date> readDates() {
        List<Date> dates = new ArrayList<>();
        for (String string : readStrings()) {
            dates.add(parseDate(string));
        }

        return dates;
    }

    /**
     * Reads an list of string elements with same element name, assuming that the cursor is positioned at the start
     * element that contains the value of the string and ends when different element name found.
     */
    public List<String> readStrings() {
        List<String> values = new ArrayList<>();
        String startingLocalName = reader.getLocalName();
        String currentLocalName = startingLocalName;

        while (forward()) {
            currentLocalName = reader.getLocalName();
            if (currentLocalName.equals(startingLocalName)) {
                values.add(readString());
            }
            else {
                skip();
            }
        }
        return values;
    }

    public BigInteger parseInteger(String image) {
        try {
            return new BigInteger(image);
        }
        catch (NumberFormatException exception) {
            throw new XmlException("The text \"" + image + "\" isn't a valid integer value");
        }
    }

    public BigDecimal parseDecimal(String image) {
        try {
            return new BigDecimal(image).stripTrailingZeros();
        }
        catch (NumberFormatException exception) {
            throw new XmlException("The text \"" + image + "\" isn't a valid decimal value");
        }
    }

    public Date parseDate(String image) {
        try {
            return DATE_FORMAT.get().parse(image);
        }
        catch(ParseException exception) {
            throw new XmlException("The text \"" + image + "\" isn't a valid date value");
        }
    }

    public boolean parseBoolean(String image) {
        if (image.equalsIgnoreCase("false") || image.equals("0")) {
            return false;
        }
        else if (image.equalsIgnoreCase("true") || image.equals("1")) {
            return true;
        }
        else {
            throw new XmlException("The text \"" + image + "\" isn't a valid boolean value");
        }
    }

    /**
     * Closes the XML document and the underlying source.
     */
    public void close() {
        try {
            reader.close();
        }
        catch (XMLStreamException exception) {
            throw new XmlException("Can't close", exception);
        }
    }
}
