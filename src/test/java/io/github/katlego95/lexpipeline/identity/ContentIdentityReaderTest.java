package io.github.katlego95.lexpipeline.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

class ContentIdentityReaderTest {

    /** Independently produced: {@code shasum -a 256 src/test/resources/samples/*.xml}. */
    private static final String VALID_SAMPLE_SHA =
            "9e28764d79c51e3d8b1403531ce41b699f6dbd80dcf44b10bd8ab0b09856674a";
    private static final String MINIMAL_SAMPLE_SHA =
            "3cccd08a531fe76e26654d8fce3fcd79163bb84a0a99960905d0d6b6e8f1787b";

    private final ContentIdentityReader reader = new ContentIdentityReader();

    private static Resource sample(String name) {
        return new ClassPathResource("samples/" + name);
    }

    private static Resource xml(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readsTheContentIdFromTheHeader() {
        ContentIdentity identity = reader.identify(sample("valid-judgment.xml"));

        assertThat(identity.contentId()).isEqualTo("FR-2024-CA-000123");
    }

    @Test
    void hashesTheRawReceivedBytes() {
        // Compared against a digest computed outside this codebase, so the test would catch a
        // change of algorithm, of encoding, or of what exactly gets fed to the digest.
        assertThat(reader.identify(sample("valid-judgment.xml")).sha256())
                .isEqualTo(VALID_SAMPLE_SHA);
        assertThat(reader.identify(sample("minimal-judgment.xml")).sha256())
                .isEqualTo(MINIMAL_SAMPLE_SHA);
    }

    @Test
    void theSameBytesAlwaysProduceTheSameIdentity() {
        assertThat(reader.identify(sample("valid-judgment.xml")))
                .isEqualTo(reader.identify(sample("valid-judgment.xml")));
    }

    /**
     * The documented trade-off of hashing bytes rather than canonical XML: a whitespace-only
     * change is a new delivery and will publish a new version. C14N before hashing is the
     * refinement, deliberately deferred — this test pins the current behaviour so the decision
     * stays visible rather than becoming folklore.
     */
    @Test
    void whitespaceOnlyChangesProduceADifferentHash() {
        String base = """
                <?xml version="1.0" encoding="UTF-8"?>
                <judgment xmlns="urn:lex:content:1"><header><content_id>FR-1</content_id>\
                </header></judgment>""";
        String reformatted = base.replace("><", ">\n<");

        assertThat(reader.identify(xml(base)).sha256())
                .isNotEqualTo(reader.identify(xml(reformatted)).sha256());
    }

    /**
     * The proof that the reader stops at the header instead of parsing the document: everything
     * after the header here is not XML at all, and a full parse would fail. Only the digest pass
     * reads to the end, and that pass never parses.
     */
    @Test
    void stopsReadingOnceTheHeaderHasGivenUpTheId() {
        Resource truncated = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <judgment xmlns="urn:lex:content:1">
                  <header>
                    <content_id>FR-2024-CA-000123</content_id>
                  </header>
                  <<< this is not XML and a full parse would never get past it
                """);

        ContentIdentity identity = reader.identify(truncated);

        assertThat(identity.contentId()).isEqualTo("FR-2024-CA-000123");
        assertThat(identity.sha256()).hasSize(64);
    }

    @Test
    void ignoresAnElementOfTheSameNameInAnotherNamespace() {
        Resource decoy = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <judgment xmlns="urn:lex:content:1" xmlns:other="urn:somewhere:else">
                  <header>
                    <other:content_id>NOT-THIS-ONE</other:content_id>
                    <content_id>FR-2024-CA-000123</content_id>
                  </header>
                </judgment>
                """);

        assertThat(reader.identify(decoy).contentId()).isEqualTo("FR-2024-CA-000123");
    }

    @Test
    void aDocumentWithNoContentIdIsAPipelineDefectNotASilentEmptyString() {
        Resource headerless = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <judgment xmlns="urn:lex:content:1"><header/></judgment>
                """);

        assertThatThrownBy(() -> reader.identify(headerless))
                .isInstanceOf(ContentIdentityException.class)
                .hasMessageContaining("pipeline defect");
    }

    @Test
    void anEmptyContentIdIsRejected() {
        Resource blank = xml("""
                <?xml version="1.0" encoding="UTF-8"?>
                <judgment xmlns="urn:lex:content:1"><header><content_id>  </content_id>\
                </header></judgment>
                """);

        assertThatThrownBy(() -> reader.identify(blank))
                .isInstanceOf(ContentIdentityException.class)
                .hasMessageContaining("empty");
    }
}
