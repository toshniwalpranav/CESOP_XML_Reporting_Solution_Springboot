package com.example.cesop.xml;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/** Namespaces plus small, XXE-safe DOM helpers. */
public final class Dom {
    public static final String DIP = "http://itzbund.de/ozg/bzst/post/dip/v2/";
    public static final String CESOP = "urn:ec.europa.eu:taxud:fiscalis:cesop:v1";
    public static final String CM = "urn:eu:taxud:commontypes:v1";
    public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";
    public static final String XMLNS = "http://www.w3.org/2000/xmlns/";

    private Dom() {}

    private static DocumentBuilder builder() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(new ErrorHandler() {
                public void warning(SAXParseException e) { }
                public void error(SAXParseException e) throws SAXException { throw e; }
                public void fatalError(SAXParseException e) throws SAXException { throw e; }
            });
            return b;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Document parse(byte[] xml) throws SAXException, IOException {
        return builder().parse(new ByteArrayInputStream(xml));
    }

    public static Document newDocument() {
        Document d = builder().newDocument();
        d.setXmlStandalone(true);
        return d;
    }

    public static List<Element> kids(Element parent, String ns, String local) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && ns.equals(e.getNamespaceURI()) && local.equals(e.getLocalName())) out.add(e);
        }
        return out;
    }

    public static Element kid(Element parent, String ns, String local) {
        List<Element> l = kids(parent, ns, local);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Trimmed text content or null when absent/blank. */
    public static String text(Element e) {
        if (e == null) return null;
        String t = e.getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    public static String text(Element parent, String ns, String local) {
        return text(kid(parent, ns, local));
    }

    public static String attr(Element e, String name) {
        if (e == null || !e.hasAttribute(name)) return null;
        String v = e.getAttribute(name);
        return v.isBlank() ? null : v.trim();
    }

    public static byte[] serialize(Document d) {
        try {
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            t.transform(new DOMSource(d), new StreamResult(bos));
            return bos.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("XML serialization failed", e);
        }
    }
}
