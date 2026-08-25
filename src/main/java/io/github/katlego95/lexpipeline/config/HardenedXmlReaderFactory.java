package io.github.katlego95.lexpipeline.config;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * The one place the pipeline's XML parsing policy is defined.
 *
 * <p>Every stage that parses a submitted document parses it the same way. Validation and the
 * transform each read the document with their own parser, and a policy that lives in two places
 * drifts: the stage with the weaker settings decides what actually gets through, and a rejection
 * ends up attributed to whichever stage happened to be strict that week.
 *
 * <p><strong>The policy.</strong> No DOCTYPE, no external entities, no external DTD, secure
 * processing on. A judgment governed by {@code judgment.xsd} has no legitimate use for a DTD —
 * the schema is the contract, and nothing in it depends on entity declarations — so refusing the
 * declaration outright is both the strongest XXE defence and the simplest rule to explain.
 *
 * <p>Refusing the declaration is stricter than merely switching external entities off, and
 * deliberately so: with the entity features off but DOCTYPE allowed, Xerces <em>skips</em> an
 * external entity reference instead of failing, and the document publishes with that text
 * silently missing. A document that was rejected can be re-sent; a document that was published
 * with a hole in it is a wrong answer with a citation attached.
 */
@Component
public class HardenedXmlReaderFactory {

    /**
     * Also used to recognise the resulting parse failure. The parser names this feature in the
     * message it raises, so matching on the URI — a constant this class supplies, not prose —
     * is how a DOCTYPE rejection is told apart from ordinary malformed XML without the
     * classification depending on Xerces' wording.
     */
    public static final String DISALLOW_DOCTYPE_FEATURE =
            "http://apache.org/xml/features/disallow-doctype-decl";

    private static final String EXTERNAL_GENERAL_ENTITIES =
            "http://xml.org/sax/features/external-general-entities";
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";
    private static final String LOAD_EXTERNAL_DTD =
            "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    private final SAXParserFactory factory;

    public HardenedXmlReaderFactory() {
        this.factory = SAXParserFactory.newInstance();
        try {
            // Namespace awareness is not optional here: every path in the stylesheets and every
            // element declaration in the schema is namespace-qualified.
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE_FEATURE, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException(
                    "XML parser does not support the hardening features; refusing to start", e);
        }
    }

    /**
     * @return a reader carrying the policy above. One per document: an {@link XMLReader} holds
     *         parse state and is not thread-safe. The factory itself is configured once in this
     *         constructor and never mutated, so handing out readers from it is.
     */
    public XMLReader newReader() {
        try {
            return factory.newSAXParser().getXMLReader();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("Could not create a hardened XML reader", e);
        }
    }

    /**
     * @return true if this failure is the parser refusing a DOCTYPE declaration, rather than the
     *         document being malformed for some ordinary reason. Walks the cause chain because
     *         the failure reaches callers wrapped differently depending on the stage.
     */
    public static boolean isDoctypeRejection(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null && message.contains(DISALLOW_DOCTYPE_FEATURE)) {
                return true;
            }
        }
        return false;
    }
}
