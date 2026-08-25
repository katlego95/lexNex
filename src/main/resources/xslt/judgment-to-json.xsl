<?xml version="1.0" encoding="UTF-8"?>
<!--
  judgment-to-json.xsl — judgment XML to the normalized JSON artifact.

  Strategy: build the W3C XML representation of JSON (the "JSON vocabulary": map/array/string
  elements in the http://www.w3.org/2005/xpath-functions namespace) as a temporary tree, then hand
  that tree to xml-to-json(). The stylesheet therefore never writes a brace, a quote or a comma —
  the serializer does, and it is the serializer's job to escape whatever a French judgment throws
  at it. See notes/xslt-walkthrough.md for the line-by-line reasoning.
-->
<xsl:stylesheet version="3.0"
                xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
                xmlns:xs="http://www.w3.org/2001/XMLSchema"
                xmlns:lex="urn:lex:content:1"
                xmlns:j="http://www.w3.org/2005/xpath-functions"
                exclude-result-prefixes="xs lex j">

  <!-- The result of xml-to-json() is a string, so the serialization method is text, not xml. -->
  <xsl:output method="text" encoding="UTF-8"/>

  <!--
    Turns off XSLT's built-in template rules. Without this, an element with no matching template
    would fall through to the built-in rule, which recurses into children and copies their text —
    silently pasting stray source text into the JSON tree. Here an unmatched node is a hard error.
  -->
  <xsl:mode on-no-match="fail"/>

  <!--
    Entry point: build the JSON tree, then serialize it in one step.

    indent=false is deliberate. Saxon's indented form starts with a newline and a stray leading
    space, which would put junk at the head of every published artifact; compact output is also
    byte-for-byte reproducible, which matters for an artifact store that versions by content.
    Two known, harmless serializer choices: xml-to-json escapes "/" as "\/" (permitted by
    RFC 8259 and decoded back to "/" by every parser), and it does not preserve insignificant
    whitespace. The tests compare parsed JSON, not bytes, for exactly that reason.
  -->
  <xsl:template match="/">
    <xsl:variable name="json-xml" as="element(j:map)">
      <xsl:apply-templates select="lex:judgment"/>
    </xsl:variable>
    <xsl:value-of select="xml-to-json($json-xml, map { 'indent' : false() })"/>
  </xsl:template>

  <!--
    The shape of the artifact, in the order the keys appear in it. Scalars are pushed through
    apply-templates; the three arrays are declared here so that they exist even when the source
    omits the optional citations/parties containers — a consumer should never have to tell
    "absent" from "empty".
  -->
  <xsl:template match="lex:judgment">
    <j:map>
      <xsl:apply-templates select="lex:header/(lex:content_id | lex:title | lex:court
                                              | lex:jurisdiction | lex:decision_date)"/>
      <j:array key="citations">
        <xsl:apply-templates select="lex:header/lex:citations/lex:citation"/>
      </j:array>
      <j:array key="parties">
        <xsl:apply-templates select="lex:header/lex:parties/lex:party"/>
      </j:array>
      <j:array key="paragraphs">
        <xsl:apply-templates select="lex:body/lex:section/lex:p"/>
      </j:array>
      <j:string key="full_text">
        <xsl:value-of select="lex:body/lex:section/lex:p ! normalize-space(.)" separator=" "/>
      </j:string>
    </j:map>
  </xsl:template>

  <!--
    Header scalars. One template for all five: the JSON key is the source element's local name,
    which is what makes the mapping obvious to a reviewer holding both files side by side.
    local-name() drops the lex: prefix deliberately — the namespace is a source-side concern.
  -->
  <xsl:template match="lex:content_id | lex:title | lex:court | lex:jurisdiction
                       | lex:decision_date">
    <j:string key="{local-name()}">
      <xsl:value-of select="normalize-space(.)"/>
    </j:string>
  </xsl:template>

  <!-- Citation record: the type attribute becomes a field, so citations stay machine-typed
       (ECLI vs NOR) instead of collapsing into an opaque string. -->
  <xsl:template match="lex:citation">
    <j:map>
      <j:string key="type">
        <xsl:value-of select="@type"/>
      </j:string>
      <j:string key="value">
        <xsl:value-of select="normalize-space(.)"/>
      </j:string>
    </j:map>
  </xsl:template>

  <!-- Party record: same shape, role attribute plus the party name as element content. -->
  <xsl:template match="lex:party">
    <j:map>
      <j:string key="role">
        <xsl:value-of select="@role"/>
      </j:string>
      <j:string key="name">
        <xsl:value-of select="normalize-space(.)"/>
      </j:string>
    </j:map>
  </xsl:template>

  <!--
    Paragraph record. id is the citation anchor. section is read from the PARENT section element
    (../@type): the source nests paragraphs inside sections, the artifact flattens them into one
    ordered list, so each record has to carry its section down with it. That denormalization is
    what lets a retrieved paragraph be weighted (reasons vs disposition) without a join back to
    the document.
  -->
  <xsl:template match="lex:p">
    <j:map>
      <j:string key="id">
        <xsl:value-of select="@id"/>
      </j:string>
      <j:string key="section">
        <xsl:value-of select="../@type"/>
      </j:string>
      <j:string key="text">
        <xsl:value-of select="normalize-space(.)"/>
      </j:string>
    </j:map>
  </xsl:template>

</xsl:stylesheet>
