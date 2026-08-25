package io.github.katlego95.lexpipeline.identity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Establishes a document's identity without ever holding the document in memory.
 *
 * <p>Two passes, both O(1) in document size:
 * <ul>
 *   <li><strong>content_id</strong> — a StAX <em>pull</em> reader, which hands back one event at a
 *       time and lets the caller stop. It stops the moment the header has given up the id, so a
 *       200 MB judgment costs the same as a 2 KB one. A DOM parse would build the whole tree to
 *       read one string; even a SAX push parse would run to the end of the document, because
 *       there is no way to tell a push parser "that is enough".</li>
 *   <li><strong>sha256</strong> — a {@link DigestInputStream} over the raw bytes, consumed in
 *       fixed-size blocks. The digest is of what arrived, byte for byte.</li>
 * </ul>
 */
@Component
public class ContentIdentityReader {

    private static final String CONTENT_NAMESPACE = "urn:lex:content:1";
    private static final String CONTENT_ID_ELEMENT = "content_id";
    private static final int DIGEST_BLOCK_BYTES = 8192;

    private final XMLInputFactory inputFactory;

    public ContentIdentityReader() {
        this.inputFactory = XMLInputFactory.newInstance();
        // Same posture as the other two stages: no DTD, therefore no entity expansion and no
        // external fetch. StAX spells the policy differently from SAX, but it is the same policy.
        inputFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        inputFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        inputFactory.setProperty(XMLInputFactory.IS_COALESCING, true);
    }

    public ContentIdentity identify(Resource document) {
        return new ContentIdentity(readContentId(document), sha256(document));
    }

    /**
     * Pulls events until the {@code content_id} element yields its text, then stops. The reader is
     * closed in a finally block precisely because the happy path abandons it mid-document.
     */
    private String readContentId(Resource document) {
        try (InputStream in = document.getInputStream()) {
            XMLStreamReader reader = inputFactory.createXMLStreamReader(in);
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && CONTENT_ID_ELEMENT.equals(reader.getLocalName())
                            && CONTENT_NAMESPACE.equals(reader.getNamespaceURI())) {
                        // getElementText consumes to the matching end tag and returns the text.
                        String contentId = reader.getElementText().trim();
                        if (contentId.isEmpty()) {
                            throw new ContentIdentityException(
                                    "content_id is empty in " + document.getDescription());
                        }
                        return contentId; // stop here: nothing after the header is needed
                    }
                }
            } finally {
                reader.close();
            }
            throw new ContentIdentityException(
                    "No content_id element found in " + document.getDescription()
                            + "; the schema makes it mandatory, so this is a pipeline defect");
        } catch (XMLStreamException e) {
            throw new ContentIdentityException(
                    "Could not read content_id from " + document.getDescription(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + document.getDescription(), e);
        }
    }

    private String sha256(Resource document) {
        try (InputStream in = document.getInputStream();
                DigestInputStream digesting = new DigestInputStream(in, newDigest())) {
            byte[] block = new byte[DIGEST_BLOCK_BYTES];
            while (digesting.read(block) != -1) {
                // The read itself feeds the digest; nothing is retained.
            }
            return HexFormat.of().formatHex(digesting.getMessageDigest().digest());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + document.getDescription(), e);
        }
    }

    private MessageDigest newDigest() {
        try {
            // Per call: MessageDigest is stateful and not thread-safe.
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }
}
