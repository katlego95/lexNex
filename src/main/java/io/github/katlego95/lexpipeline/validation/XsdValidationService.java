package io.github.katlego95.lexpipeline.validation;

import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.validation.SizeLimitedInputStream.LimitExceededException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;
import org.xml.sax.SAXNotRecognizedException;
import org.xml.sax.SAXNotSupportedException;
import org.xml.sax.SAXParseException;

/**
 * The trust gate: decides whether a document is allowed to become content.
 *
 * <p>One bad document in the corpus becomes a wrong answer with a citation attached, so nothing
 * that fails here is ever published — it is quarantined with the diagnostics that explain why.
 *
 * <p><strong>Why the JDK and not Saxon.</strong> {@code javax.xml.validation} is implemented in
 * the JDK by Xerces, which is a full XSD 1.0 processor. Saxon-HE has no XSD validation at all
 * (that is a Saxon-EE feature), so validation uses Xerces and only the transform uses Saxon.
 *
 * <p><strong>Memory.</strong> {@code Validator.validate(StreamSource)} is SAX-driven: it pushes
 * events and never materialises a tree, so validation is O(1) in document size. Transform is the
 * one stage that has to hold a document in memory.
 *
 * <p><strong>Threading.</strong> {@link Schema} is immutable and thread-safe, so it is compiled
 * once at startup. {@link Validator} is <em>not</em>: it carries per-document parse state, so a
 * fresh one is created per call. Sharing one across the worker pool would interleave two
 * documents' parse states and produce diagnostics that belong to neither.
 */
@Service
public class XsdValidationService {

    private static final Logger log = LoggerFactory.getLogger(XsdValidationService.class);

    /**
     * XXE hardening. Setting these two properties to the empty string means "no protocol is
     * allowed" when the parser tries to fetch an external DTD or an external schema document.
     *
     * <p>The attack this closes: a submitted judgment declares
     * {@code <!DOCTYPE judgment [<!ENTITY x SYSTEM "file:///etc/passwd">]>} and puts {@code &x;}
     * in a paragraph. A parser that resolves external entities reads that file off the server's
     * disk and helpfully substitutes it into the document we then transform, index and serve —
     * server-side file disclosure, or SSRF if the URL is {@code http://169.254.169.254/...}.
     * The same door allows billion-laughs style entity expansion and denial of service.
     *
     * <p>Empty string is deliberate: it is the JAXP-defined value for "deny everything", where
     * omitting the property leaves the JDK default, which permits {@code file} and {@code http}.
     */
    private static final String NO_EXTERNAL_ACCESS = "";

    private final Schema schema;
    private final long maxDocBytes;

    public XsdValidationService(AppProperties properties, ResourceLoader resourceLoader) {
        this.maxDocBytes = properties.maxDocBytes();
        this.schema = compileSchema(resourceLoader.getResource(properties.xsdPath()));
        log.info("XSD compiled from {} (max document size {} bytes)",
                properties.xsdPath(), this.maxDocBytes);
    }

    /**
     * Validates one document and returns a verdict; it does not throw for a bad document, because
     * a bad document is an expected outcome of the pipeline rather than an error in it.
     *
     * @param document a re-readable byte source — a file, a classpath entry, or the received
     *                 request body wrapped in a {@code ByteArrayResource}
     * @throws UncheckedIOException if the source itself cannot be read. That is an infrastructure
     *                              failure (STORAGE_FAILED), not a verdict about the content, so
     *                              it is deliberately not folded into {@link ValidationResult}.
     */
    public ValidationResult validate(Resource document) {
        long declaredLength = declaredLength(document);
        if (declaredLength > maxDocBytes) {
            // Cheapest possible rejection: no parser is created, no byte is read.
            return ValidationResult.oversize(declaredLength, maxDocBytes);
        }

        CollectingErrorHandler errorHandler = new CollectingErrorHandler();
        try (InputStream raw = document.getInputStream();
                InputStream bounded = new SizeLimitedInputStream(raw, maxDocBytes)) {

            Validator validator = schema.newValidator();
            harden(validator);
            validator.setErrorHandler(errorHandler);
            validator.validate(new StreamSource(bounded));

        } catch (LimitExceededException e) {
            return ValidationResult.oversize(-1, e.limit());
        } catch (SAXException e) {
            // Xerces rethrows a fatal error after the handler returns; the handler has already
            // recorded it, so there is nothing to add unless the throw bypassed the handler.
            LimitExceededException oversize = findLimitExceeded(e);
            if (oversize != null) {
                return ValidationResult.oversize(-1, oversize.limit());
            }
            return ValidationResult.malformed(fallbackDiagnostics(errorHandler, e));
        } catch (IOException e) {
            LimitExceededException oversize = findLimitExceeded(e);
            if (oversize != null) {
                return ValidationResult.oversize(-1, oversize.limit());
            }
            throw new UncheckedIOException("Could not read " + document.getDescription(), e);
        }

        if (errorHandler.hasFatalError()) {
            return ValidationResult.malformed(errorHandler.diagnostics());
        }
        if (errorHandler.hasErrors()) {
            return ValidationResult.schemaInvalid(errorHandler.diagnostics());
        }
        return ValidationResult.ok();
    }

    /**
     * Compiled once, at startup. If the schema itself is broken or missing, the application must
     * not start: a service that cannot run its trust gate has nothing safe to do with traffic.
     */
    private Schema compileSchema(Resource xsd) {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        harden(factory);
        try (InputStream in = xsd.getInputStream()) {
            // systemId is passed so that any error message names the file, not "unknown source".
            return factory.newSchema(new StreamSource(in, systemId(xsd)));
        } catch (IOException | SAXException e) {
            throw new IllegalStateException(
                    "Could not compile XSD from " + xsd.getDescription()
                            + "; refusing to start without a working trust gate", e);
        }
    }

    private void harden(SchemaFactory factory) {
        try {
            // Turns on the JDK's own limits (entity expansion, name length) as well.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, NO_EXTERNAL_ACCESS);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, NO_EXTERNAL_ACCESS);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new IllegalStateException(
                    "XML parser does not support the XXE hardening properties; refusing to start",
                    e);
        }
    }

    /**
     * The same two properties again, on the validator. They are not inherited from the factory:
     * the factory settings govern fetching the <em>schema</em>, these govern fetching anything the
     * <em>instance document</em> points at, which is the direction an attacker controls.
     */
    private void harden(Validator validator) {
        try {
            validator.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, NO_EXTERNAL_ACCESS);
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, NO_EXTERNAL_ACCESS);
        } catch (SAXNotRecognizedException | SAXNotSupportedException e) {
            throw new IllegalStateException(
                    "XML parser does not support the XXE hardening properties", e);
        }
    }

    /** @return the source's length in bytes, or -1 when it cannot be known without reading it. */
    private long declaredLength(Resource document) {
        try {
            return document.contentLength();
        } catch (IOException e) {
            return -1;
        }
    }

    private String systemId(Resource xsd) {
        try {
            return xsd.getURL().toExternalForm();
        } catch (IOException e) {
            return xsd.getDescription();
        }
    }

    /**
     * A fatal error normally reaches the handler first. If a parser throws without calling it,
     * the exception itself is the only diagnostic we have, so it is converted rather than lost.
     */
    private List<Diagnostic> fallbackDiagnostics(CollectingErrorHandler handler, SAXException e) {
        if (!handler.diagnostics().isEmpty()) {
            return handler.diagnostics();
        }
        SAXParseException parseException = e instanceof SAXParseException spe
                ? spe
                : new SAXParseException(e.getMessage(), null, null, -1, -1, e);
        return List.of(Diagnostic.from(Diagnostic.Severity.FATAL, parseException));
    }

    private LimitExceededException findLimitExceeded(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof LimitExceededException limit) {
                return limit;
            }
        }
        return null;
    }
}
