package io.github.katlego95.lexpipeline.transform;

import io.github.katlego95.lexpipeline.config.AppProperties;
import io.github.katlego95.lexpipeline.config.HardenedXmlReaderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import net.sf.saxon.s9api.Processor;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XsltCompiler;
import net.sf.saxon.s9api.XsltExecutable;
import net.sf.saxon.s9api.XsltTransformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.xml.sax.InputSource;

/**
 * Turns a validated judgment into its published artifacts, one stylesheet per artifact.
 *
 * <p><strong>Why Saxon.</strong> The JDK ships Xalan, which implements XSLT 1.0. Everything the
 * normalization leans on — {@code xml-to-json()}, the JSON vocabulary, {@code xsl:mode}, the
 * {@code !} operator — is XSLT 3.0, and Saxon-HE is the open-licence processor that implements it.
 *
 * <p><strong>Threading.</strong> An {@link XsltExecutable} is the compiled stylesheet: immutable,
 * thread-safe, and expensive to produce, so one is compiled per stylesheet at startup and shared.
 * An {@link XsltTransformer} holds the state of one run (source, destination, parameters), so a
 * fresh one is loaded per document. Compiling per document would re-parse and re-optimise the
 * stylesheet for every judgment; sharing one transformer across the worker pool would let two
 * documents write into each other's run.
 *
 * <p><strong>Memory.</strong> This is the one stage that must materialise the document: Saxon
 * builds a TinyTree. Peak memory is therefore roughly concurrency x largest document, bounded
 * explicitly by APP_MAX_DOC_BYTES and the worker count. Streaming XSLT would remove that ceiling
 * but is a Saxon-EE feature.
 */
@Service
public class XsltTransformService {

    private static final Logger log = LoggerFactory.getLogger(XsltTransformService.class);

    private static final String NORMALIZED_JSON_STYLESHEET = "judgment-to-json.xsl";
    private static final String FULL_TEXT_STYLESHEET = "fulltext.xsl";

    private final Processor processor;
    private final XsltExecutable normalizedJson;
    private final XsltExecutable fullText;
    private final HardenedXmlReaderFactory readerFactory;

    public XsltTransformService(AppProperties properties, ResourceLoader resourceLoader,
            HardenedXmlReaderFactory readerFactory) {
        this.processor = new Processor(false); // false = no licensed features, i.e. Saxon-HE
        this.readerFactory = readerFactory;

        XsltCompiler compiler = processor.newXsltCompiler();
        String base = properties.xsltPath();
        this.normalizedJson = compile(compiler, resourceLoader, base + NORMALIZED_JSON_STYLESHEET);
        this.fullText = compile(compiler, resourceLoader, base + FULL_TEXT_STYLESHEET);

        log.info("Stylesheets compiled from {}: {}, {}",
                base, NORMALIZED_JSON_STYLESHEET, FULL_TEXT_STYLESHEET);
    }

    /** @return true once every stylesheet is compiled and documents can be transformed. */
    public boolean isReady() {
        return normalizedJson != null && fullText != null;
    }

    /** @return the stylesheets compiled at startup, for the readiness report. */
    public List<String> stylesheets() {
        return List.of(NORMALIZED_JSON_STYLESHEET, FULL_TEXT_STYLESHEET);
    }

    /** @return the normalized JSON artifact for a document that has already passed validation. */
    public String toNormalizedJson(Resource document) {
        return transform(normalizedJson, document, NORMALIZED_JSON_STYLESHEET);
    }

    /** @return the RAG-ready plain-text artifact: every paragraph, document order, single spaces. */
    public String toFullText(Resource document) {
        return transform(fullText, document, FULL_TEXT_STYLESHEET);
    }

    private String transform(XsltExecutable stylesheet, Resource document, String stylesheetName) {
        try (InputStream in = document.getInputStream()) {
            StringWriter output = new StringWriter();
            Serializer destination = processor.newSerializer(output);

            // Per document: an XsltTransformer is single-run state, not a shared service.
            XsltTransformer transformer = stylesheet.load();
            transformer.setSource(hardenedSource(in, document));
            transformer.setDestination(destination);
            transformer.transform();

            return output.toString();
        } catch (SaxonApiException e) {
            throw new TransformFailedException(
                    "%s failed on %s: %s".formatted(
                            stylesheetName, document.getDescription(), e.getMessage()), e);
        } catch (IOException e) {
            // Reading the source is infrastructure, not a verdict on the content; same split as
            // the validation service makes.
            throw new UncheckedIOException("Could not read " + document.getDescription(), e);
        }
    }

    /**
     * Compiled once, at startup. A stylesheet that will not compile is a deployment defect, so the
     * application refuses to start rather than discovering it on the first document.
     */
    private XsltExecutable compile(XsltCompiler compiler, ResourceLoader loader, String location) {
        Resource stylesheet = loader.getResource(location);
        List<String> errors = new ArrayList<>();
        // Saxon reports each compilation error here; without this the exception message is only
        // "Errors were reported during stylesheet compilation" and the detail goes to stderr.
        compiler.setErrorReporter(error -> errors.add(describe(error)));
        try (InputStream in = stylesheet.getInputStream()) {
            return compiler.compile(new StreamSource(in, systemId(stylesheet)));
        } catch (IOException | SaxonApiException e) {
            throw new IllegalStateException(
                    "Could not compile stylesheet " + location
                            + (errors.isEmpty() ? "" : ": " + String.join("; ", errors)), e);
        } finally {
            compiler.setErrorReporter(null);
        }
    }

    private String describe(net.sf.saxon.s9api.XmlProcessingError error) {
        javax.xml.transform.SourceLocator location = error.getLocation();
        String where = location != null && location.getLineNumber() > 0
                ? " (line " + location.getLineNumber() + ")"
                : "";
        return error.getMessage() + where;
    }

    /**
     * The transform parses the document a second time, so it parses it under the same policy as
     * the trust gate — the shared {@link HardenedXmlReaderFactory}, not a private copy of the
     * settings. A document with a DOCTYPE should already have been quarantined at validation;
     * this is defence in depth for the paths that reach a transform another way.
     */
    private SAXSource hardenedSource(InputStream in, Resource document) {
        InputSource source = new InputSource(in);
        source.setSystemId(document.getDescription());
        return new SAXSource(readerFactory.newReader(), source);
    }

    private String systemId(Resource stylesheet) {
        try {
            return stylesheet.getURL().toExternalForm();
        } catch (IOException e) {
            return stylesheet.getDescription();
        }
    }
}
